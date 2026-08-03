@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementCompilerSymbolKind
import io.github.amichne.kast.api.contract.result.ReplacementCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSignature
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementPropertySignature
import io.github.amichne.kast.api.contract.result.ReplacementTypeParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementTypeVariance
import io.github.amichne.kast.api.contract.result.ReplacementValueParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.protocol.ReplacementProofFailureEvidence
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedReplacementPlanQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression

private data class ReplacementPlanningSnapshot(
    val target: SymbolIdentity,
    val generation: Long,
    val sourceRange: Location,
    val oldSignature: ReplacementDeclarationSignature,
    val proposedSignature: ReplacementDeclarationSignature,
    val outboundReferences: List<ExactReplacementOutboundReference>,
    val sourceContextHash: String,
)

private data class ParsedProposedDeclaration(
    val declaration: KtNamedDeclaration,
    val nameOffset: Int,
)

private data class RawOutboundReference(
    val startOffset: Int,
    val endOffset: Int,
    val sourceText: String,
    val target: RawCompilerTarget,
)

private sealed interface RawCompilerTarget {
    data class Source(
        val element: PsiElement,
        val externalFallback: ReplacementOutboundTarget.External,
    ) : RawCompilerTarget

    data class External(val target: ReplacementOutboundTarget.External) : RawCompilerTarget
}

internal suspend fun KastIndexerBackend.planReplacementOperation(
    query: ParsedReplacementPlanQuery,
): ReplacementPlanResult = withContext(readDispatcher) {
    telemetry.inSpan(IdeaTelemetryScope.PLAN_REPLACEMENT, "kast.idea.planReplacement") {
        val snapshot = timedReadAction(
            telemetry,
            IdeaTelemetryScope.PLAN_REPLACEMENT,
            "kast.idea.planReplacement.collect",
        ) {
            collectReplacementPlanningSnapshot(query)
        }
        val edit = TextEdit(
            filePath = snapshot.sourceRange.filePath,
            startOffset = snapshot.sourceRange.startOffset,
            endOffset = snapshot.sourceRange.endOffset,
            newText = query.proposedDeclaration.value,
        )
        val fileImages = try {
            planExactMutationFileImages(listOf(edit))
        } catch (failure: ExactMutationFileImagePlanningException) {
            failReplacementProof(
                limitation = ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
                message = "Replacement exact source image proof failed: ${failure.failure.name}",
                knownMinimumCount = snapshot.outboundReferences.size,
            )
        }
        val fileHashes = fileImages.map { image ->
            FileHash(
                filePath = image.filePath.value,
                hash = image.preimage.sha256.value,
            )
        }
        val proof = timedReadAction(
            telemetry,
            IdeaTelemetryScope.PLAN_REPLACEMENT,
            "kast.idea.planReplacement.prove",
        ) {
            finalizeReplacementProof(
                query = query,
                snapshot = snapshot,
                fileHashes = fileHashes,
            )
        }
        ReplacementPlanResult.of(
            edit = edit,
            proof = proof,
            fileImages = fileImages,
        )
    }
}

private fun KastIndexerBackend.collectReplacementPlanningSnapshot(
    query: ParsedReplacementPlanQuery,
): ReplacementPlanningSnapshot {
    if (query.target.kind != SymbolKind.FUNCTION && query.target.kind != SymbolKind.PROPERTY) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
            "Replacement planning supports only Kotlin function and property targets",
        )
    }
    val file = findKtFile(query.target.declarationFile.value)
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset == query.target.declarationStartOffset.value
        }
        .singleOrNull()
        ?: failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target could not be proven at its compiler declaration offset",
        )
    if (target !is KtNamedFunction && target !is KtProperty) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
            "The exact replacement target is not a Kotlin function or property",
        )
    }
    requireNoReplacementAnnotations(target)
    val targetIdentity = compilerSourceIdentity(target)
    if (targetIdentity != query.target) {
        failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The supplied replacement identity does not match the compiler-resolved declaration",
        )
    }

    val proposedText = query.proposedDeclaration.value
    val parsedProposal = parseProposedDeclaration(proposedText)
    requireNoReplacementAnnotations(parsedProposal.declaration)
    val targetRange = target.textRange
    val syntheticText = file.text.replaceRange(targetRange.startOffset, targetRange.endOffset, proposedText)
    val syntheticFile = KtPsiFactory.contextual(target).createFile(file.name, syntheticText)
    val proposedNameOffset = targetRange.startOffset + parsedProposal.nameOffset
    val proposed = PsiTreeUtil.findChildrenOfType(syntheticFile, KtNamedDeclaration::class.java)
        .filter { declaration -> declaration.nameIdentifier?.textRange?.startOffset == proposedNameOffset }
        .singleOrNull()
        ?: failReplacementProof(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed declaration could not be analyzed in the target source context",
        )
    if (proposed::class != parsedProposal.declaration::class) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "The context-backed proposed declaration kind changed during parsing",
        )
    }

    val oldSignature = compilerReplacementSignature(target)
    val proposedSignature = compilerReplacementSignature(proposed)
    if (oldSignature != proposedSignature) {
        failReplacementProof(
            ReplacementProofLimitation.SIGNATURE_DRIFT,
            "The proposed declaration changes its compiler-observable signature",
        )
    }
    val outboundReferences = collectExactOutboundReferences(
        originalFile = file,
        syntheticFile = syntheticFile,
        proposed = proposed,
        replacementStartOffset = targetRange.startOffset,
        originalDeclarationLength = targetRange.length,
        proposedDeclarationText = proposedText,
    )
    return ReplacementPlanningSnapshot(
        target = targetIdentity,
        generation = psiGeneration(),
        sourceRange = target.toKastLocation(targetRange),
        oldSignature = oldSignature,
        proposedSignature = proposedSignature,
        outboundReferences = outboundReferences,
        sourceContextHash = FileHashing.sha256(file.text),
    )
}

private fun KastIndexerBackend.finalizeReplacementProof(
    query: ParsedReplacementPlanQuery,
    snapshot: ReplacementPlanningSnapshot,
    fileHashes: List<FileHash>,
): ExactReplacementProof {
    val occurrenceCount = snapshot.outboundReferences.size
    if (psiGeneration() != snapshot.generation) {
        failReplacementProof(
            ReplacementProofLimitation.GENERATION_CHANGED,
            "The semantic generation changed before replacement proof finalization",
            occurrenceCount,
        )
    }
    val file = findKtFile(snapshot.target.declarationFile.value)
    val currentContextHash = FileHashing.sha256(file.text)
    if (currentContextHash != snapshot.sourceContextHash ||
        fileHashes.singleOrNull()?.let { hash ->
            hash.filePath == snapshot.target.declarationFile.value
        } != true
    ) {
        failReplacementProof(
            ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED,
            "The exact source context changed while the replacement proof was being built",
            occurrenceCount,
        )
    }
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset == snapshot.target.declarationStartOffset.value
        }
        .singleOrNull()
        ?: failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target disappeared before proof finalization",
            occurrenceCount,
        )
    if (compilerSourceIdentity(target) != snapshot.target || target.toKastLocation(target.textRange) != snapshot.sourceRange) {
        failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target changed before proof finalization",
            occurrenceCount,
        )
    }
    if (psiGeneration() != snapshot.generation) {
        failReplacementProof(
            ReplacementProofLimitation.GENERATION_CHANGED,
            "The semantic generation changed during replacement proof finalization",
            occurrenceCount,
        )
    }
    return ExactReplacementProof.of(
        target = snapshot.target,
        requiredGeneration = MutationSemanticGeneration(snapshot.generation),
        sourceRange = snapshot.sourceRange,
        fileHashes = fileHashes,
        oldSignature = snapshot.oldSignature,
        proposedSignature = snapshot.proposedSignature,
        proposedDeclarationHash = ReplacementDeclarationSha256(
            FileHashing.sha256(query.proposedDeclaration.value),
        ),
        proposedDeclarationLength = query.proposedDeclaration.value.length,
        evidence = ReplacementOutboundEvidence.Complete.of(occurrenceCount),
        outboundReferences = snapshot.outboundReferences,
    )
}

private fun KastIndexerBackend.parseProposedDeclaration(text: String): ParsedProposedDeclaration {
    val parsed = KtPsiFactory(project).createFile("KastProposedReplacement.kt", text)
    val declarations = parsed.declarations
    if (declarations.isEmpty()) {
        failReplacementProof(
            ReplacementProofLimitation.ZERO_REPLACEMENT_DECLARATIONS,
            "The proposed replacement must contain exactly one Kotlin declaration",
        )
    }
    if (declarations.size > 1) {
        failReplacementProof(
            ReplacementProofLimitation.MULTIPLE_REPLACEMENT_DECLARATIONS,
            "The proposed replacement contains more than one Kotlin declaration",
        )
    }
    if (PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java) != null) {
        failReplacementProof(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed replacement declaration contains Kotlin syntax errors",
        )
    }
    val declaration = declarations.single() as? KtNamedDeclaration
        ?: failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "The proposed replacement is not a named Kotlin declaration",
        )
    if (declaration !is KtNamedFunction && declaration !is KtProperty) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "Replacement planning supports only Kotlin function and property declarations",
        )
    }
    val declarationRange = declaration.textRange
    if (text.substring(0, declarationRange.startOffset).isNotBlank() ||
        text.substring(declarationRange.endOffset).isNotBlank()
    ) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
            "The proposed replacement must contain only one declaration",
        )
    }
    return ParsedProposedDeclaration(
        declaration = declaration,
        nameOffset = declaration.nameIdentifier?.textRange?.startOffset
            ?: failReplacementProof(
                ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
                "The proposed replacement declaration has no compiler-visible name",
            ),
    )
}

internal fun compilerReplacementSignature(
    declaration: KtNamedDeclaration,
): ReplacementDeclarationSignature = try {
    analyze(declaration) {
        when (val symbol = declaration.symbol) {
            is KaNamedFunctionSymbol -> symbol.replacementSignature()
            is KaKotlinPropertySymbol -> symbol.replacementSignature()
            else -> null
        }
    } ?: failReplacementProof(
        ReplacementProofLimitation.COMPILER_SIGNATURE_UNPROVEN,
        "K2 could not prove every required replacement signature property",
    )
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (failure: ReplacementProofIncompleteException) {
    throw failure
} catch (_: Exception) {
    failReplacementProof(
        ReplacementProofLimitation.COMPILER_SIGNATURE_UNPROVEN,
        "K2 could not prove every required replacement signature property",
    )
}

private fun KaNamedFunctionSymbol.replacementSignature(): ReplacementFunctionSignature? =
    ReplacementFunctionSignature.of(
        name = name.asString(),
        receiverType = receiverParameter?.returnType?.canonicalReplacementType(),
        contextReceiverTypes = contextReceivers.map { receiver -> receiver.type.canonicalReplacementType() },
        typeParameters = typeParameters.map(KaTypeParameterSymbol::replacementSignature),
        valueParameters = valueParameters.map(KaValueParameterSymbol::replacementSignature),
        returnType = returnType.canonicalReplacementType(),
        visibility = visibility.toReplacementVisibility() ?: return null,
        modality = modality.toReplacementModality(),
        hasStableParameterNames = hasStableParameterNames,
        suspend = isSuspend,
        operator = isOperator,
        inline = isInline,
        override = isOverride,
        infix = isInfix,
        static = isStatic,
        tailrec = isTailRec,
        external = isExternal,
        expect = isExpect,
        actual = isActual,
    )

private fun KaKotlinPropertySymbol.replacementSignature(): ReplacementPropertySignature? {
    val exactGetter = getter ?: return null
    if (!hasGetter) return null
    val exactSetter = setter
    if (hasSetter != (exactSetter != null)) return null
    return ReplacementPropertySignature.of(
        name = name.asString(),
        receiverType = receiverParameter?.returnType?.canonicalReplacementType(),
        contextReceiverTypes = contextReceivers.map { receiver -> receiver.type.canonicalReplacementType() },
        typeParameters = typeParameters.map(KaTypeParameterSymbol::replacementSignature),
        returnType = returnType.canonicalReplacementType(),
        visibility = visibility.toReplacementVisibility() ?: return null,
        modality = modality.toReplacementModality(),
        getterVisibility = exactGetter.visibility.toReplacementVisibility() ?: return null,
        setterVisibility = exactSetter?.visibility?.toReplacementVisibility(),
        hasGetter = hasGetter,
        hasSetter = hasSetter,
        hasBackingField = hasBackingField,
        isVal = isVal,
        const = isConst,
        lateinit = isLateInit,
        delegated = isDelegatedProperty,
        override = isOverride,
        static = isStatic,
        external = isExternal,
        expect = isExpect,
        actual = isActual,
    )
}

private fun KaTypeParameterSymbol.replacementSignature(): ReplacementTypeParameterSignature =
    ReplacementTypeParameterSignature(
        name = name.asString(),
        upperBounds = upperBounds.joinToString(" & ") { bound -> bound.canonicalReplacementType() },
        variance = when (variance) {
            org.jetbrains.kotlin.types.Variance.INVARIANT -> ReplacementTypeVariance.INVARIANT
            org.jetbrains.kotlin.types.Variance.IN_VARIANCE -> ReplacementTypeVariance.IN
            org.jetbrains.kotlin.types.Variance.OUT_VARIANCE -> ReplacementTypeVariance.OUT
        },
        reified = isReified,
    )

private fun KaValueParameterSymbol.replacementSignature(): ReplacementValueParameterSignature =
    ReplacementValueParameterSignature(
        name = name.asString(),
        type = returnType.canonicalReplacementType(),
        vararg = isVararg,
        hasDefaultValue = hasDefaultValue,
        noinline = isNoinline,
        crossinline = isCrossinline,
    )

private fun org.jetbrains.kotlin.analysis.api.types.KaType.canonicalReplacementType(): String =
    toString().replace('/', '.')

private fun KaSymbolVisibility.toReplacementVisibility(): ReplacementVisibility? = when (this) {
    KaSymbolVisibility.PUBLIC -> ReplacementVisibility.PUBLIC
    KaSymbolVisibility.PROTECTED -> ReplacementVisibility.PROTECTED
    KaSymbolVisibility.INTERNAL -> ReplacementVisibility.INTERNAL
    KaSymbolVisibility.PACKAGE_PROTECTED -> ReplacementVisibility.PACKAGE_PROTECTED
    KaSymbolVisibility.PACKAGE_PRIVATE -> ReplacementVisibility.PACKAGE_PRIVATE
    KaSymbolVisibility.PRIVATE -> ReplacementVisibility.PRIVATE
    KaSymbolVisibility.LOCAL -> ReplacementVisibility.LOCAL
    KaSymbolVisibility.UNKNOWN -> null
}

private fun KaSymbolModality.toReplacementModality(): ReplacementModality = when (this) {
    KaSymbolModality.FINAL -> ReplacementModality.FINAL
    KaSymbolModality.SEALED -> ReplacementModality.SEALED
    KaSymbolModality.OPEN -> ReplacementModality.OPEN
    KaSymbolModality.ABSTRACT -> ReplacementModality.ABSTRACT
}

internal fun KastIndexerBackend.collectExactOutboundReferences(
    originalFile: KtFile,
    syntheticFile: KtFile,
    proposed: KtNamedDeclaration,
    replacementStartOffset: Int,
    originalDeclarationLength: Int,
    proposedDeclarationText: String,
): List<ExactReplacementOutboundReference> {
    requireNoUnhandledImplicitReplacementReferences(proposed)
    val rawReferences = try {
        analyze(proposed) {
            val exactCallTargets = mutableMapOf<KtReferenceExpression, KaSymbol>()
            PsiTreeUtil.findChildrenOfType(proposed, KtCallExpression::class.java).forEach { call ->
                when (val callInfo = call.resolveToCall()) {
                    null -> failReplacementProof(
                        ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                        "A proposed replacement call did not resolve through K2",
                    )
                    is KaErrorCallInfo -> failReplacementProof(
                        if (callInfo.candidateCalls.size > 1) {
                            ReplacementProofLimitation.OVERLOAD_AMBIGUOUS
                        } else {
                            ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED
                        },
                        "A proposed replacement call did not resolve to one exact compiler target",
                    )
                    else -> {
                        val callee = call.calleeExpression as? KtReferenceExpression
                        val exactCall = callInfo.singleFunctionCallOrNull()
                        if (exactCall is KaImplicitInvokeCall) {
                            failReplacementProof(
                                ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
                                "An implicit invoke cannot retain both compiler targets in one occurrence",
                            )
                        }
                        val symbol = exactCall?.signature?.symbol
                        if (callee != null && symbol != null) exactCallTargets[callee] = symbol
                    }
                }
            }

            val collected = mutableListOf<RawOutboundReference>()
            val traversedEveryElement = PsiTreeUtil.processElements(proposed) { element ->
                    if (element is KtCallExpression) return@processElements true
                    if (element !is KtReferenceExpression) return@processElements true
                    val references = element.references
                    if (references.isEmpty()) {
                        failReplacementProof(
                            ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
                            "A proposed Kotlin reference expression exposes no K2 reference",
                        )
                    }
                    if (references.any { reference -> reference !is KtReference }) {
                        failReplacementProof(
                            ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
                            "A proposed replacement reference lacks K2 reference provenance",
                        )
                    }
                    for (reference in references.filterIsInstance<KtReference>()) {
                        val symbol = exactCallTargets[element] ?: reference.resolveToSymbol()
                            ?: failReplacementProof(
                                ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                                "The proposed replacement reference '${element.text}' did not resolve through K2",
                            )
                        val compilerPsi = symbol.psi
                        if (compilerPsi != null && isInside(proposed, compilerPsi)) {
                            continue
                        }
                        val psiResolution = reference.resolve()
                        if (compilerPsi != null && psiResolution != null &&
                            !samePsiTarget(PsiManager.getInstance(project), compilerPsi, psiResolution)
                        ) {
                            failReplacementProof(
                                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                                "A proposed replacement reference has mismatched compiler and PSI targets",
                            )
                        }
                        val absoluteStart = element.textRange.startOffset + reference.rangeInElement.startOffset
                        val absoluteEnd = element.textRange.startOffset + reference.rangeInElement.endOffset
                        val relativeStart = absoluteStart - replacementStartOffset
                        val relativeEnd = absoluteEnd - replacementStartOffset
                        if (relativeStart < 0 || relativeEnd > proposedDeclarationText.length ||
                            relativeEnd <= relativeStart
                        ) {
                            failReplacementProof(
                                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                                "A proposed replacement reference escaped the exact declaration range",
                            )
                        }
                        val externalTarget = symbol.externalReplacementTarget()
                            ?: failReplacementProof(
                                ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                                "A proposed replacement reference has no exact compiler target identity",
                            )
                        collected.add(
                            RawOutboundReference(
                                startOffset = relativeStart,
                                endOffset = relativeEnd,
                                sourceText = proposedDeclarationText.substring(relativeStart, relativeEnd),
                                target = compilerPsi?.let { source ->
                                    RawCompilerTarget.Source(source, externalTarget)
                                } ?: RawCompilerTarget.External(externalTarget),
                            ),
                        )
                    }
                    true
            }
            if (!traversedEveryElement) {
                failReplacementProof(
                    ReplacementProofLimitation.PROPOSED_PSI_TRAVERSAL_INCOMPLETE,
                    "The proposed replacement PSI traversal stopped before completion",
                )
            }
            collected
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: ReplacementProofIncompleteException) {
        throw failure
    } catch (_: Exception) {
        throw ReplacementProofIncompleteException(
            evidence = ReplacementProofFailureEvidence.of(
                ReplacementProofLimitation.PROPOSED_PSI_TRAVERSAL_INCOMPLETE,
                ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
            ),
            message = "K2 could not complete exact proposed replacement reference traversal",
        )
    }

    val exact = rawReferences.map { raw ->
        val target = when (val compilerTarget = raw.target) {
            is RawCompilerTarget.External -> compilerTarget.target
            is RawCompilerTarget.Source -> sourceOutboundTarget(
                source = compilerTarget.element,
                fallback = compilerTarget.externalFallback,
                originalFile = originalFile,
                syntheticFile = syntheticFile,
                proposed = proposed,
                replacementStartOffset = replacementStartOffset,
                originalDeclarationLength = originalDeclarationLength,
                proposedDeclarationLength = proposedDeclarationText.length,
            )
        }
        ExactReplacementOutboundReference(
            relativeStartOffset = raw.startOffset,
            relativeEndOffset = raw.endOffset,
            sourceText = raw.sourceText,
            resolvedTarget = target,
            provenance = ReplacementOccurrenceProvenance.COMPILER,
        )
    }.sortedWith(compareBy({ it.relativeStartOffset }, { it.relativeEndOffset }))

    return exact.groupBy { reference -> reference.relativeStartOffset to reference.relativeEndOffset }
        .map { (_, sameRange) ->
            if (sameRange.map(ExactReplacementOutboundReference::resolvedTarget).distinct().size != 1) {
                failReplacementProof(
                    ReplacementProofLimitation.OUTBOUND_CARDINALITY_MISMATCH,
                    "One proposed replacement occurrence resolved to multiple compiler targets",
                )
            }
            sameRange.first()
        }
        .sortedWith(compareBy({ it.relativeStartOffset }, { it.relativeEndOffset }))
}

private fun requireNoReplacementAnnotations(declaration: KtNamedDeclaration) {
    val accessorAnnotations = (declaration as? KtProperty)
        ?.accessors
        .orEmpty()
        .flatMap { accessor -> accessor.annotationEntries }
    if (declaration.annotationEntries.isNotEmpty() || accessorAnnotations.isNotEmpty()) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
            "Initial replacement proof does not model declaration or property-accessor annotations",
        )
    }
}

private fun requireNoUnhandledImplicitReplacementReferences(declaration: KtNamedDeclaration) {
    if (declaration.hasUnhandledImplicitCallSyntax()) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
            "The proposed replacement contains implicit-call syntax that exact outbound proof does not model",
        )
    }
}

private fun KtNamedDeclaration.hasUnhandledImplicitCallSyntax(): Boolean =
    PsiTreeUtil.findChildOfType(this, KtForExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtArrayAccessExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtDestructuringDeclaration::class.java) != null ||
        (this is KtProperty && delegateExpression != null) ||
        PsiTreeUtil.findChildrenOfType(this, KtProperty::class.java).any { property ->
            property.delegateExpression != null
        }

private fun isInside(ancestor: PsiElement, element: PsiElement): Boolean =
    ancestor === element || PsiTreeUtil.isAncestor(ancestor, element, false)

private fun samePsiTarget(manager: PsiManager, first: PsiElement, second: PsiElement): Boolean =
    first === second ||
        manager.areElementsEquivalent(first, second) ||
        manager.areElementsEquivalent(first.navigationElement, second.navigationElement)

private fun KaSymbol.externalReplacementTarget(): ReplacementOutboundTarget.External? {
    val identity = when (this) {
        is KaConstructorSymbol -> containingClassId?.asSingleFqName()?.asString()?.let { owner ->
            Triple(
                "$owner.<init>",
                ReplacementCompilerSymbolKind.CONSTRUCTOR,
                compilerTargetSignature("$owner.<init>"),
            )
        }
        is KaFunctionSymbol -> callableId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.FUNCTION, compilerTargetSignature(fqName))
        }
        is KaVariableSymbol -> callableId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(
                fqName,
                ReplacementCompilerSymbolKind.PROPERTY,
                "property|$fqName|" +
                    "${receiverParameter?.returnType?.canonicalReplacementType() ?: "-"}|" +
                    returnType.canonicalReplacementType(),
            )
        }
        is KaTypeAliasSymbol -> classId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.TYPE_ALIAS, "typealias|$fqName")
        }
        is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.CLASS, "class|$fqName")
        }
        is KaPackageSymbol -> fqName.asString().takeIf(String::isNotBlank)?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.PACKAGE, "package|$fqName")
        }
        is KaValueParameterSymbol -> name.asString().takeIf(String::isNotBlank)?.let { name ->
            Triple(
                name,
                ReplacementCompilerSymbolKind.PARAMETER,
                "parameter|$name|${returnType.canonicalReplacementType()}",
            )
        }
        is KaTypeParameterSymbol -> name.asString().takeIf(String::isNotBlank)?.let { name ->
            Triple(
                name,
                ReplacementCompilerSymbolKind.TYPE_PARAMETER,
                "type-parameter|$name|" + upperBounds.joinToString("&") { it.canonicalReplacementType() },
            )
        }
        else -> null
    } ?: return null
    return ReplacementOutboundTarget.External(
        fqName = identity.first,
        kind = identity.second,
        signature = ReplacementCompilerTargetSignature(identity.third),
    )
}

private fun KaFunctionSymbol.compilerTargetSignature(fqName: String): String = buildString {
    append("function|").append(fqName).append('|')
    append(receiverParameter?.returnType?.canonicalReplacementType() ?: "-").append('|')
    append(contextReceivers.joinToString(",") { receiver -> receiver.type.canonicalReplacementType() }).append('|')
    append(valueParameters.joinToString(",") { parameter ->
        parameter.returnType.canonicalReplacementType()
    }).append('|')
    append(returnType.canonicalReplacementType()).append('|')
    append(
        when (this@compilerTargetSignature) {
            is KaNamedFunctionSymbol -> typeParameters.size
            is KaConstructorSymbol -> typeParameters.size
            else -> 0
        },
    )
}

private fun KastIndexerBackend.sourceOutboundTarget(
    source: PsiElement,
    fallback: ReplacementOutboundTarget.External,
    originalFile: KtFile,
    syntheticFile: KtFile,
    proposed: KtNamedDeclaration,
    replacementStartOffset: Int,
    originalDeclarationLength: Int,
    proposedDeclarationLength: Int,
): ReplacementOutboundTarget {
    if (source.containingFile === syntheticFile) {
        if (isInside(proposed, source)) {
            failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "An internal proposed replacement target was classified as outbound",
            )
        }
        val syntheticDeclaration = source as? KtNamedDeclaration
            ?: PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false)
            ?: failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A synthetic compiler target could not be mapped to its exact source declaration",
            )
        val syntheticNameOffset = syntheticDeclaration.nameIdentifier?.textRange?.startOffset
            ?: failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A synthetic compiler target has no exact declaration offset",
            )
        val replacementEnd = replacementStartOffset + proposedDeclarationLength
        val originalNameOffset = when {
            syntheticNameOffset < replacementStartOffset -> syntheticNameOffset
            syntheticNameOffset >= replacementEnd ->
                syntheticNameOffset - (proposedDeclarationLength - originalDeclarationLength)
            else -> failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A proposed replacement target was not retained as an internal reference",
            )
        }
        val originalDeclaration = PsiTreeUtil.findChildrenOfType(originalFile, KtNamedDeclaration::class.java)
            .filter { declaration ->
                declaration.nameIdentifier?.textRange?.startOffset == originalNameOffset &&
                    declaration.name == syntheticDeclaration.name
            }
            .singleOrNull()
            ?: failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A compiler target in the synthetic file did not map to one exact source declaration",
            )
        return ReplacementOutboundTarget.Source(compilerSourceIdentity(originalDeclaration))
    }

    val sourcePath = source.containingFile?.virtualFile?.path
    if (sourcePath == null || !isWorkspaceFile(sourcePath)) {
        return fallback
    }
    return ReplacementOutboundTarget.Source(compilerSourceIdentity(source))
}

internal fun compilerSourceIdentity(source: PsiElement): SymbolIdentity {
    val identitySource = when (source) {
        is KtNamedDeclaration, is PsiClass, is PsiMethod, is PsiField -> source
        else -> PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false) ?: source
    }
    val symbol = when (val containingFile = identitySource.containingFile) {
        is KtFile -> analyze(containingFile) {
            identitySource.toSymbolModel(
                containingDeclaration = compilerContainingDeclarationName(identitySource),
            )
        }
        else -> identitySource.toSymbolModel(
            containingDeclaration = when (identitySource) {
                is PsiMethod -> identitySource.containingClass?.qualifiedName
                is PsiField -> identitySource.containingClass?.qualifiedName
                is PsiClass -> identitySource.containingClass?.qualifiedName
                else -> null
            },
        )
    }
    return symbol.relationshipIdentity()
}

private fun failReplacementProof(
    limitation: ReplacementProofLimitation,
    message: String,
    knownMinimumCount: Int = 0,
): Nothing = throw ReplacementProofIncompleteException(
    evidence = ReplacementProofFailureEvidence.of(limitation, knownMinimumCount = knownMinimumCount),
    message = message,
)
