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
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementCompilerSymbolKind
import io.github.amichne.kast.api.contract.result.ReplacementCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
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
internal fun KastIndexerBackend.collectExactOutboundReferences(
    syntheticFile: KtFile,
    proposed: KtNamedDeclaration,
    replacementStartOffset: Int,
    proposedDeclarationText: String,
    sourceIdentityBasis: ReplacementSourceIdentityBasis,
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
                syntheticFile = syntheticFile,
                proposed = proposed,
                replacementStartOffset = replacementStartOffset,
                proposedDeclarationLength = proposedDeclarationText.length,
                sourceIdentityBasis = sourceIdentityBasis,
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

internal fun isInside(ancestor: PsiElement, element: PsiElement): Boolean =
    ancestor === element || PsiTreeUtil.isAncestor(ancestor, element, false)

private fun samePsiTarget(manager: PsiManager, first: PsiElement, second: PsiElement): Boolean =
    first === second ||
        manager.areElementsEquivalent(first, second) ||
        manager.areElementsEquivalent(first.navigationElement, second.navigationElement)
