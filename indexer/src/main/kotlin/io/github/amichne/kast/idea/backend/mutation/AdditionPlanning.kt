@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.idea.edit.IdeaLineSeparator
import io.github.amichne.kast.idea.edit.IdeaNormalizedTextEdit
import io.github.amichne.kast.idea.edit.IdeaTextImagePlanner
import io.github.amichne.kast.idea.edit.IdeaUtf16Offset
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toSymbolModel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

internal data class AdditionOwnerSnapshot(
    val owner: AdditionSourceOwner,
    val modelFingerprint: AdditionProjectModelFingerprint,
    val classpathFingerprint: AdditionClasspathFingerprint,
    val sourceFiles: List<Path>,
    val anchorSourceFiles: List<Path>,
)

internal data class ParsedAddition(
    val file: KtFile,
    val declarations: List<KtNamedDeclaration>,
    val packageIdentity: AdditionKotlinPackage,
    val analysisSource: AdditionAnalysisSource,
)

internal enum class AdditionAnalysisSource {
    COPIED_PROPOSAL,
    PROJECT_POSTIMAGE,
}

internal suspend fun KastIndexerBackend.planAddFileOperation(
    query: ParsedAddFilePlanQuery,
): AddFilePlanResult = withContext(readDispatcher) {
    telemetry.inSpan(IdeaTelemetryScope.PLAN_ADD_FILE, "kast.idea.planAddFile") {
        timedReadAction(telemetry, IdeaTelemetryScope.PLAN_ADD_FILE, "kast.idea.planAddFile.prove") {
            val target = query.targetPath.toJavaPath()
            if (!Files.isDirectory(target.parent, NOFOLLOW_LINKS)) failAddition(
                AdditionProofLimitation.TARGET_PARENT_MISSING,
                "The add-file target parent does not exist",
            )
            if (Files.exists(target, NOFOLLOW_LINKS)) failAddition(
                AdditionProofLimitation.TARGET_ALREADY_EXISTS,
                "The add-file target already exists",
            )
            val owner = exactAdditionOwner(target)
            requireSecureAbsentTarget(target, owner.owner)
            val anchor = owner.anchorSourceFiles.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .mapNotNull(::findKtFileOrNull)
                .firstOrNull()
                ?: failAddition(
                    AdditionProofLimitation.MODULE_CONTEXT_ANCHOR_UNAVAILABLE,
                    "The exact source owner has no Kotlin module-context anchor",
                )
            val parsed = parseAddFile(query.proposedContent.value, target.fileName.toString(), anchor)
            proveZeroRebindingCandidates(parsed.declarations, parsed.packageIdentity, owner.sourceFiles)
            val proofParts = proveAdditionDeclarations(parsed, relativeBaseOffset = 0)
            val generation = psiGeneration()
            val context = exactAdditionContext(owner, generation, targetToInclude = null)
            revalidateAdditionContext(owner, generation, context, target, mustExist = false)
            val postimage = strictAdditionPlannerUtf8Bytes(query.proposedContent.value)
            AddFilePlanResult.of(
                proposedContent = query.proposedContent.value,
                proof = ExactAddFileProof.of(
                    targetPath = query.targetPath,
                    owner = owner.owner,
                    packageIdentity = parsed.packageIdentity,
                    declarations = proofParts.declarations,
                    context = context,
                    collisionEvidence = ExactAdditionCollisionEvidence.complete(proofParts.declarations.size),
                    outboundEvidence = proofParts.outbound,
                    rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
                    postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(postimage)),
                ),
            )
        }
    }
}

internal suspend fun KastIndexerBackend.planAddDeclarationOperation(
    query: ParsedAddDeclarationPlanQuery,
): AddDeclarationPlanResult = withContext(readDispatcher) {
    telemetry.inSpan(IdeaTelemetryScope.PLAN_ADD_DECLARATION, "kast.idea.planAddDeclaration") {
        timedReadAction(
            telemetry,
            IdeaTelemetryScope.PLAN_ADD_DECLARATION,
            "kast.idea.planAddDeclaration.prove",
        ) {
            val target = query.targetPath.toJavaPath()
            if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) failAddition(
                AdditionProofLimitation.TARGET_FILE_MISSING,
                "The add-declaration target file does not exist",
            )
            val rawPreimage = secureAdditionRead(target)
            val actualPrehash = FileHashing.sha256(rawPreimage)
            if (actualPrehash != query.expectedCurrentSha256.value) failAddition(
                AdditionProofLimitation.TARGET_FILE_HASH_CHANGED,
                "The exact add-declaration target preimage changed",
            )
            val targetFile = findKtFileOrNull(target) ?: failAddition(
                AdditionProofLimitation.TARGET_NOT_KOTLIN_SOURCE,
                "The add-declaration target is not one exact Kotlin source file",
            )
            val owner = exactAdditionOwner(target)
            requireSecureExistingTarget(target, owner.owner)
            val parsedProposal = parseAddDeclaration(query.proposedDeclaration.value, targetFile)
            val insertion = SemanticInsertionPointResolver.resolve(
                targetFile,
                ParsedSemanticInsertionQuery(
                    position = ParsedFilePosition(NormalizedPath.parse(target.toString()), ByteOffset(0)),
                    target = SemanticInsertionTarget.FILE_BOTTOM,
                ),
            ).insertionOffset
            if (insertion != targetFile.textLength) failAddition(
                AdditionProofLimitation.FILE_BOTTOM_UNAVAILABLE,
                "Compiler FILE_BOTTOM did not equal the normalized document length",
            )
            val separator = when {
                targetFile.text.isEmpty() || targetFile.text.endsWith("\n\n") -> ""
                targetFile.text.endsWith('\n') -> "\n"
                else -> "\n\n"
            }
            val appendText = separator + query.proposedDeclaration.value + "\n"
            val syntheticText = targetFile.text + appendText
            val syntheticFile = copiedAdditionKtFile(targetFile, syntheticText, targetFile.name)
            requireSyntaxComplete(syntheticFile)
            val proposedStart = insertion + separator.length
            val proposed = syntheticFile.declarations.filterIsInstance<KtNamedDeclaration>()
                .singleOrNull { it.textRange.startOffset == proposedStart }
                ?: failAddition(
                    AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
                    "The proposed declaration could not be analyzed in the target compiler context",
                )
            val contextual = ParsedAddition(
                file = syntheticFile,
                declarations = listOf(proposed),
                packageIdentity = targetFile.packageFqName.toAdditionPackage(),
                analysisSource = AdditionAnalysisSource.COPIED_PROPOSAL,
            )
            proveZeroRebindingCandidates(contextual.declarations, contextual.packageIdentity, owner.sourceFiles)
            val proofParts = proveAdditionDeclarations(contextual, relativeBaseOffset = proposedStart)
            val generation = psiGeneration()
            val context = exactAdditionContext(owner, generation, targetToInclude = target)
            revalidateAdditionContext(owner, generation, context, target, mustExist = true)
            val imagePlan = try {
                IdeaTextImagePlanner.plan(
                    rawPreimage = rawPreimage,
                    normalizedDocumentText = targetFile.text,
                    edits = listOf(
                        IdeaNormalizedTextEdit(
                            startOffset = IdeaUtf16Offset(insertion),
                            endOffset = IdeaUtf16Offset(insertion),
                            replacementText = appendText,
                        ),
                    ),
                    replacementLineSeparator = IdeaLineSeparator.LF,
                )
            } catch (_: IllegalArgumentException) {
                failAddition(
                    AdditionProofLimitation.NEWLINE_POLICY_UNPROVEN,
                    "The exact add-declaration text image could not be proven",
                )
            }
            val image = imagePlan.exactFileImage(target.toString())
            AddDeclarationPlanResult.of(
                proposedDeclaration = query.proposedDeclaration.value,
                image = image,
                proof = ExactAddDeclarationProof.of(
                    targetPath = query.targetPath,
                    targetPreimageSha256 = query.expectedCurrentSha256,
                    owner = owner.owner,
                    packageIdentity = contextual.packageIdentity,
                    declaration = proofParts.declarations.single(),
                    insertion = CompilerFileBottomInsertion.at(insertion),
                    newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
                    context = context,
                    collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
                    outboundEvidence = proofParts.outbound,
                    rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
                    postimageSha256 = AdditionPostimageSha256.of(image.postimage.sha256.value),
                ),
            )
        }
    }
}

private fun KastIndexerBackend.parseAddFile(content: String, fileName: String, anchor: KtFile): ParsedAddition {
    val file = copiedAdditionKtFile(anchor, content, fileName)
    requireSyntaxComplete(file)
    val declarations = file.declarations.map { declaration ->
        declaration as? KtNamedDeclaration ?: failAddition(
            AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
            "Every proposed top-level declaration must have one compiler-visible name",
        )
    }
    if (declarations.isEmpty()) failAddition(
        AdditionProofLimitation.ZERO_DECLARATIONS,
        "Add-file source must contain at least one top-level declaration",
    )
    return ParsedAddition(
        file,
        declarations,
        file.packageFqName.toAdditionPackage(),
        AdditionAnalysisSource.COPIED_PROPOSAL,
    )
}

private fun KastIndexerBackend.parseAddDeclaration(content: String, target: KtFile): ParsedAddition {
    val file = copiedAdditionKtFile(target, content, "KastProposedDeclaration.kt")
    requireSyntaxComplete(file)
    if (file.packageDirective?.text?.isNotBlank() == true || file.importDirectives.isNotEmpty()) failAddition(
        AdditionProofLimitation.MULTIPLE_DECLARATIONS,
        "Add-declaration content must not contain a package or import directive",
    )
    if (file.declarations.isEmpty()) failAddition(
        AdditionProofLimitation.ZERO_DECLARATIONS,
        "Add-declaration content must contain exactly one declaration",
    )
    if (file.declarations.size != 1) failAddition(
        AdditionProofLimitation.MULTIPLE_DECLARATIONS,
        "Add-declaration content must contain exactly one declaration",
    )
    val declaration = file.declarations.single() as? KtNamedDeclaration ?: failAddition(
        AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
        "The proposed top-level declaration must have one compiler-visible name",
    )
    if (content.substring(0, declaration.textRange.startOffset).isNotBlank() ||
        content.substring(declaration.textRange.endOffset).isNotBlank()
    ) failAddition(
        AdditionProofLimitation.MULTIPLE_DECLARATIONS,
        "Add-declaration content must contain only one declaration",
    )
    return ParsedAddition(
        file,
        listOf(declaration),
        target.packageFqName.toAdditionPackage(),
        AdditionAnalysisSource.COPIED_PROPOSAL,
    )
}

internal fun copiedAdditionKtFile(original: KtFile, content: String, fileName: String): KtFile {
    val copy = PsiFileFactory.getInstance(original.project)
        .createFileFromText(content, original) as? KtFile
        ?: failAddition(
            AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
            "The proposal copy factory did not retain Kotlin PSI",
        )
    if (copy.copyOrigin !== original) failAddition(
        AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
        "The proposal PSI is not an exact copy of the admitted compiler context",
    )
    if (copy.name != fileName) copy.setName(fileName)
    if (copy.name != fileName || copy.copyOrigin !== original) failAddition(
        AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
        "The proposal PSI did not retain its admitted origin and exact target name",
    )
    return copy
}

private fun requireSyntaxComplete(file: KtFile) {
    if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) failAddition(
        AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
        "The proposed Kotlin source contains syntax errors",
    )
}

internal data class AdditionProofParts(
    val declarations: List<AdditionTopLevelDeclaration>,
    val outbound: ExactAdditionOutboundEvidence,
)

internal fun KastIndexerBackend.proveAdditionDeclarations(
    parsed: ParsedAddition,
    relativeBaseOffset: Int,
): AdditionProofParts {
    val declarations = parsed.declarations.map { declaration ->
        requireNoUnhandledImplicitAdditionReferences(declaration)
        AdditionTopLevelDeclaration.of(
            packageIdentity = parsed.packageIdentity,
            name = declaration.name ?: failAddition(
                AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
                "The proposed declaration has no compiler-visible name",
            ),
            kind = declaration.additionKind(),
            relativeStartOffset = declaration.textRange.startOffset - relativeBaseOffset,
            relativeEndOffset = declaration.textRange.endOffset - relativeBaseOffset,
            collisionSignature = compilerAdditionSignature(declaration),
        )
    }
    if (declarations.distinctBy { declaration ->
            when (declaration.kind) {
                AdditionTopLevelDeclarationKind.CLASS,
                AdditionTopLevelDeclarationKind.INTERFACE,
                AdditionTopLevelDeclarationKind.OBJECT,
                AdditionTopLevelDeclarationKind.ENUM_CLASS,
                AdditionTopLevelDeclarationKind.ANNOTATION_CLASS,
                AdditionTopLevelDeclarationKind.TYPE_ALIAS,
                -> listOf(declaration.packageIdentity, declaration.name, "CLASSIFIER")

                AdditionTopLevelDeclarationKind.FUNCTION,
                AdditionTopLevelDeclarationKind.PROPERTY,
                -> listOf(
                    declaration.packageIdentity,
                    declaration.name,
                    declaration.kind,
                    declaration.collisionSignature,
                )
            }
        }.size != declarations.size
    ) failAddition(
        AdditionProofLimitation.DECLARATION_COLLISION,
        "Proposed declarations contain a compiler collision that differs only outside declaration identity",
    )
    proveNoCompilerCollision(parsed, declarations)
    val outbound = parsed.declarations.flatMap { declaration ->
        collectAdditionOutbound(
            parsed.file,
            parsed.declarations,
            declaration,
            relativeBaseOffset,
            parsed.analysisSource,
        )
    }
    return AdditionProofParts(declarations, ExactAdditionOutboundEvidence.complete(outbound))
}

private fun requireNoUnhandledImplicitAdditionReferences(declaration: KtNamedDeclaration) {
    if (declaration.hasUnhandledImplicitCallSyntax()) failAddition(
        AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
        "The proposed addition contains implicit-call syntax that exact outbound proof does not model",
    )
}

private fun KtNamedDeclaration.hasUnhandledImplicitCallSyntax(): Boolean =
    PsiTreeUtil.findChildOfType(this, KtForExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtArrayAccessExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtDestructuringDeclaration::class.java) != null ||
        (this is KtProperty && delegateExpression != null) ||
        PsiTreeUtil.findChildrenOfType(this, KtProperty::class.java).any { property ->
            property.delegateExpression != null
        }

private fun KtNamedDeclaration.additionKind(): AdditionTopLevelDeclarationKind = when (this) {
    is KtClass -> when {
        isInterface() -> AdditionTopLevelDeclarationKind.INTERFACE
        isEnum() -> AdditionTopLevelDeclarationKind.ENUM_CLASS
        isAnnotation() -> AdditionTopLevelDeclarationKind.ANNOTATION_CLASS
        else -> AdditionTopLevelDeclarationKind.CLASS
    }
    is KtObjectDeclaration -> AdditionTopLevelDeclarationKind.OBJECT
    is KtNamedFunction -> AdditionTopLevelDeclarationKind.FUNCTION
    is KtProperty -> AdditionTopLevelDeclarationKind.PROPERTY
    is KtTypeAlias -> AdditionTopLevelDeclarationKind.TYPE_ALIAS
    else -> failAddition(
        AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
        "The proposed Kotlin top-level declaration kind is unsupported",
    )
}

private fun compilerAdditionSignature(declaration: KtNamedDeclaration): AdditionDeclarationCollisionSignature {
    val signature = try {
        analyze(declaration) {
            declaration.symbol.additionCollisionSignature()
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    } ?: failAddition(
        AdditionProofLimitation.DECLARATION_COLLISION,
        "K2 could not produce an exact declaration collision signature",
    )
    return AdditionDeclarationCollisionSignature.of(FileHashing.sha256(signature))
}

private fun KaSymbol.additionCollisionSignature(): String? = when (this) {
    is KaFunctionSymbol -> buildString {
        append("function|").append(callableId?.asSingleFqName()?.asString()).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() }).append('|')
        append(typeParameters.joinToString(",") { parameter ->
            parameter.upperBounds.joinToString("&") { it.toString() }
        }).append('|')
        append(valueParameters.joinToString(",") { it.returnType.toString() })
    }
    is KaVariableSymbol -> buildString {
        append("property|").append(callableId?.asSingleFqName()).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() })
    }
    is KaClassLikeSymbol -> "class|${classId?.asSingleFqName()}"
    is KaTypeAliasSymbol -> "typealias|${classId?.asSingleFqName()}"
    else -> null
}

private fun KastIndexerBackend.proveNoCompilerCollision(
    parsed: ParsedAddition,
    declarations: List<AdditionTopLevelDeclaration>,
) {
    val packageFqName = parsed.packageIdentity.toFqName()
    try {
        analyze(parsed.file) {
            declarations.zip(parsed.declarations).forEach { (evidence, proposedPsi) ->
                when (evidence.kind) {
                    AdditionTopLevelDeclarationKind.CLASS,
                    AdditionTopLevelDeclarationKind.INTERFACE,
                    AdditionTopLevelDeclarationKind.OBJECT,
                    AdditionTopLevelDeclarationKind.ENUM_CLASS,
                    AdditionTopLevelDeclarationKind.ANNOTATION_CLASS,
                    AdditionTopLevelDeclarationKind.TYPE_ALIAS,
                    -> {
                        val fqName = packageFqName.child(Name.identifier(evidence.name))
                        val kotlinCollision = findClassLike(ClassId.topLevel(fqName))
                            ?.psi
                            ?.takeUnless { target -> target.isProposedAdditionTarget(parsed.declarations) }
                        val jvmCollision = JavaPsiFacade.getInstance(project)
                            .findClasses(fqName.asString(), GlobalSearchScope.allScope(project))
                            .any { target -> !target.isProposedAdditionTarget(parsed.declarations) }
                        if (kotlinCollision != null || jvmCollision) failAddition(
                            AdditionProofLimitation.DECLARATION_COLLISION,
                            "A proposed classifier already exists in source or dependency scope",
                        )
                    }

                    AdditionTopLevelDeclarationKind.FUNCTION,
                    AdditionTopLevelDeclarationKind.PROPERTY,
                    -> {
                        val collision = findTopLevelCallables(packageFqName, Name.identifier(evidence.name))
                            .filter { symbol -> !symbol.psi.isProposedAdditionTarget(parsed.declarations) }
                            .any { symbol ->
                                symbol.additionCollisionSignature()?.let(FileHashing::sha256) ==
                                    evidence.collisionSignature.value
                            }
                        if (collision) failAddition(
                            AdditionProofLimitation.DECLARATION_COLLISION,
                            "A proposed callable signature already exists in source or dependency scope",
                        )
                    }
                }
                check(proposedPsi.name == evidence.name)
            }
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: AdditionProofIncompleteException) {
        throw failure
    } catch (_: Exception) {
        failAddition(
            AdditionProofLimitation.COMPILER_COLLISION_SCOPE_INCOMPLETE,
            "K2 could not exhaust source and dependency collision lookup",
        )
    }
}

private fun PsiElement?.isProposedAdditionTarget(proposed: List<KtNamedDeclaration>): Boolean =
    this != null && listOf(this, navigationElement).distinct().any { candidate ->
        proposed.any { declaration ->
            candidate === declaration || PsiTreeUtil.isAncestor(declaration, candidate, false)
        }
    }

private fun AdditionKotlinPackage.toFqName(): FqName = when (this) {
    AdditionKotlinPackage.Root -> FqName.ROOT
    is AdditionKotlinPackage.Named -> FqName(segments.joinToString(".") { segment -> segment.value })
}

private fun KastIndexerBackend.collectAdditionOutbound(
    proposedFile: KtFile,
    proposedDeclarations: List<KtNamedDeclaration>,
    declaration: KtNamedDeclaration,
    relativeBaseOffset: Int,
    analysisSource: AdditionAnalysisSource,
): List<ExactAdditionOutboundOccurrence> = try {
    val collect: KaSession.() -> List<ExactAdditionOutboundOccurrence> = {
        val proposalInternalSymbols = proposedDeclarations.map { proposed -> proposed.symbol }
        val exactCallTargets = mutableMapOf<KtReferenceExpression, KaSymbol>()
        PsiTreeUtil.findChildrenOfType(declaration, KtCallExpression::class.java).forEach { call ->
            val callSite = (call.parent as? KtDotQualifiedExpression)
                ?.takeIf { qualified -> qualified.selectorExpression === call }
                ?: call
            when (val callInfo = callSite.resolveToCall()) {
                null -> failAddition(
                    AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "A proposed addition call did not resolve through K2",
                )
                is KaErrorCallInfo -> failAddition(
                    if (callInfo.candidateCalls.size > 1) {
                        AdditionProofLimitation.OVERLOAD_AMBIGUOUS
                    } else {
                        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED
                    },
                    "The proposed addition call '${call.text}' did not resolve to one exact compiler target: " +
                        "${callInfo.diagnostic.factoryName}: ${callInfo.diagnostic.defaultMessage}",
                )
                else -> {
                    val exactCall = callInfo.singleFunctionCallOrNull()
                    if (exactCall is KaImplicitInvokeCall) failAddition(
                        AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
                        "An implicit invoke cannot retain every compiler target in one addition occurrence",
                    )
                    val callee = call.calleeExpression as? KtReferenceExpression
                    val symbol = exactCall?.signature?.symbol
                    if (callee != null && symbol != null) exactCallTargets[callee] = symbol
                }
            }
        }
        PsiTreeUtil.findChildrenOfType(declaration, KtReferenceExpression::class.java).mapNotNull { expression ->
            if (expression is KtCallExpression) return@mapNotNull null
            val references = expression.references.filterIsInstance<KtReference>()
            if (references.isEmpty()) failAddition(
                AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                "A proposed Kotlin reference has no compiler reference",
            )
            val targets = exactCallTargets[expression]?.let(::listOf) ?: references.map { reference ->
                reference.resolveToSymbol() ?: failAddition(
                    AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "The proposed Kotlin reference '${expression.text}' did not resolve through K2 " +
                        "via ${reference::class.qualifiedName}",
                )
            }.distinct()
            if (targets.size != 1) failAddition(
                AdditionProofLimitation.OVERLOAD_AMBIGUOUS,
                "A proposed Kotlin reference did not resolve to one exact target",
            )
            val symbol = targets.single()
            if (symbol is KaPackageSymbol) {
                if (expression.isQualifiedPackageSegment()) return@mapNotNull null
                failAddition(
                    AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "A compiler package symbol was not an exact qualified-reference segment",
                )
            }
            val compilerPsi = symbol.psi
            if (proposalInternalSymbols.any { internal -> internal == symbol } ||
                compilerPsi.isWithinProposedFile(proposedFile)
            ) {
                return@mapNotNull null
            }
            val range = expression.textRange
            ExactAdditionOutboundOccurrence.of(
                relativeStartOffset = range.startOffset - relativeBaseOffset,
                relativeEndOffset = range.endOffset - relativeBaseOffset,
                resolvedTarget = symbol.toAdditionTarget(this@collectAdditionOutbound),
            )
        }
    }
    when (analysisSource) {
        AdditionAnalysisSource.COPIED_PROPOSAL -> analyzeCopy(
            declaration,
            KaDanglingFileResolutionMode.PREFER_SELF,
            collect,
        )
        AdditionAnalysisSource.PROJECT_POSTIMAGE -> analyze(declaration, collect)
    }
} catch (failure: AdditionProofIncompleteException) {
    throw failure
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    failAddition(
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        "K2 could not retain exact outbound occurrence evidence",
    )
}

private fun PsiElement?.isWithinProposedFile(proposedFile: KtFile): Boolean =
    this != null && listOf(this, navigationElement).distinct().any { candidate ->
        candidate === proposedFile || PsiTreeUtil.isAncestor(proposedFile, candidate, false)
    }

private fun KtReferenceExpression.isQualifiedPackageSegment(): Boolean {
    val qualified = parent as? KtDotQualifiedExpression ?: return false
    return qualified.receiverExpression === this || qualified.selectorExpression === this
}

private fun KaSymbol.toAdditionTarget(backend: KastIndexerBackend): AdditionResolvedTarget {
    val source = psi
    val sourcePath = source?.containingFile?.virtualFile?.path
    if (source != null && sourcePath != null && backend.isWorkspaceFile(sourcePath)) {
        return AdditionResolvedTarget.Source.of(additionSourceIdentity(source))
    }
    val fqName = when (this) {
        is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()
        is KaCallableSymbol -> callableId?.asSingleFqName()?.asString()
        else -> null
    } ?: failAddition(
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        "An outbound compiler target has no canonical identity",
    )
    val kind = when (this) {
        is KaNamedFunctionSymbol, is KaConstructorSymbol -> SymbolKind.FUNCTION
        is KaVariableSymbol -> SymbolKind.PROPERTY
        is KaClassLikeSymbol -> SymbolKind.CLASS
        is KaTypeAliasSymbol -> SymbolKind.CLASS
        else -> SymbolKind.UNKNOWN
    }
    if (kind == SymbolKind.UNKNOWN) failAddition(
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        "An outbound compiler target has an unsupported symbol kind",
    )
    return AdditionResolvedTarget.External.of(
        fqName = fqName,
        kind = kind,
        compilerSignature = AdditionCompilerTargetSignature.of(stableAdditionTargetSignature(fqName)),
    )
}

private fun KaSymbol.stableAdditionTargetSignature(fqName: String): String = when (this) {
    is KaFunctionSymbol -> buildString {
        append("function|").append(fqName).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() }).append('|')
        append(typeParameters.joinToString(",") { parameter ->
            parameter.upperBounds.joinToString("&") { it.toString() }
        }).append('|')
        append(valueParameters.joinToString(",") { it.returnType.toString() }).append('|')
        append(returnType)
    }
    is KaVariableSymbol -> buildString {
        append("property|").append(fqName).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() }).append('|')
        append(returnType)
    }
    is KaTypeAliasSymbol -> "typealias|$fqName|${expandedType}"
    is KaClassLikeSymbol -> "class|$fqName"
    else -> "symbol|$fqName|${this::class.qualifiedName}"
}

private fun additionSourceIdentity(source: PsiElement): SymbolIdentity {
    val identitySource = when (source) {
        is KtNamedDeclaration, is PsiClass, is PsiMethod, is PsiField -> source
        else -> PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false) ?: source
    }
    return when (val file = identitySource.containingFile) {
        is KtFile -> analyze(file) {
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
    }.relationshipIdentity()
}

internal fun KastIndexerBackend.proveZeroRebindingCandidates(
    declarations: List<KtNamedDeclaration>,
    packageIdentity: AdditionKotlinPackage,
    sourceFiles: List<Path>,
    excludedRanges: Map<String, List<IntRange>> = emptyMap(),
) {
    val admittedFiles = sourceFiles.mapNotNull { path ->
        LocalFileSystem.getInstance().findFileByNioFile(path)
    }
    if (admittedFiles.size != sourceFiles.size) failAddition(
        AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
        "Every model-owned Kotlin and Java source file must have IntelliJ index admission",
    )
    val exactSourceScope = GlobalSearchScope.filesScope(project, admittedFiles)
    val names = declarations.map { declaration -> declaration.name ?: failAddition(
        AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
        "A proposed declaration has no compiler-visible name",
    ) }.distinct()
    declarations.forEach { declaration ->
        if (declaration.hasModifier(KtTokens.OPERATOR_KEYWORD) || declaration.name in KOTLIN_CONVENTION_NAMES) {
            failAddition(
                AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
                "Operator and Kotlin convention declarations cannot prove an empty implicit lookup scope",
            )
        }
    }
    names.forEach { name ->
        var collision = false
        var occurrence = false
        val complete = try {
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                { element, offsetInElement ->
                    val file = element.containingFile
                    val virtualFile = file?.virtualFile
                    if (virtualFile == null || !ProjectFileIndex.getInstance(project).isInSource(virtualFile) ||
                        (file !is KtFile && file !is PsiJavaFile)
                    ) return@processElementsWithWord true
                    val occurrenceOffset = element.textRange.startOffset + offsetInElement
                    val excluded = excludedRanges[virtualFile.path].orEmpty().any { range ->
                        occurrenceOffset in range
                    }
                    if (excluded) return@processElementsWithWord true
                    occurrence = true
                    val occurrenceElement = file.findElementAt(occurrenceOffset) ?: element
                    val kotlinDeclaration = PsiTreeUtil.getParentOfType(
                        occurrenceElement,
                        KtNamedDeclaration::class.java,
                        false,
                    )
                    if (kotlinDeclaration?.name == name && kotlinDeclaration.parent is KtFile &&
                        (kotlinDeclaration.containingKtFile.packageFqName.toAdditionPackage() == packageIdentity)
                    ) collision = true
                    true
                },
                exactSourceScope,
                name,
                UsageSearchContext.IN_CODE,
                true,
            )
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        }
        if (!complete) failAddition(
            AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
            "Indexed Kotlin and Java rebinding scope traversal did not complete",
        )
        if (collision) failAddition(
            AdditionProofLimitation.DECLARATION_COLLISION,
            "A proposed declaration collides with an existing declaration in its Kotlin package",
        )
        if (occurrence) failAddition(
            AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
            "A proposed declaration name already occurs in Kotlin or Java source; virtual rebinding is unproven",
        )
    }
}

internal fun KastIndexerBackend.exactAdditionOwner(target: Path): AdditionOwnerSnapshot {
    val model = workspaceModelReader()
    if (!model.importedModelComplete()) failAddition(
        AdditionProofLimitation.PROJECT_MODEL_INCOMPLETE,
        "The imported Gradle project model is incomplete",
    )
    val normalizedTarget = target.toAbsolutePath().normalize()
    val candidates = model.moduleAssociations().flatMap { module ->
        module.sourceSets().flatMap { sourceSet ->
            sourceSet.sourceRoots().mapNotNull { rawRoot ->
                val sourceRoot = rawRoot.toAbsolutePath().normalize()
                sourceRoot.takeIf { normalizedTarget != it && normalizedTarget.startsWith(it) }?.let {
                    Triple(module, sourceSet, sourceRoot)
                }
            }
        }
    }
    val mostSpecificDepth = candidates.maxOfOrNull { it.third.nameCount } ?: failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The target has no exact Gradle source-set owner",
    )
    val exact = candidates.filter { it.third.nameCount == mostSpecificDepth }.distinctBy { candidate ->
        listOf(
            candidate.first.ideaModuleName(),
            candidate.first.linkedBuildRoot().toString(),
            candidate.first.gradleProjectPath(),
            candidate.second.sourceSetName(),
            candidate.third.toString(),
        )
    }
    if (exact.size != 1) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS,
        "The target has more than one exact Gradle source-set owner",
    )
    val (module, sourceSet, sourceRoot) = exact.single()
    val sourceFiles = model.moduleAssociations().flatMap { association ->
        association.sourceSets().flatMap { it.sourceRoots() }
    }.map { it.toAbsolutePath().normalize() }
        .distinct()
        .flatMap(::sourceFilesUnder)
        .distinct()
        .sortedBy(Path::toString)
    val anchorSourceFiles = sourceFilesUnder(sourceRoot)
    return AdditionOwnerSnapshot(
        owner = AdditionSourceOwner.of(
            sourceRoot = AdditionSourceRoot.parse(sourceRoot.toString()),
            ideaModuleName = AdditionIdeaModuleName.of(module.ideaModuleName()),
            gradleBuildRoot = AdditionGradleBuildRoot.parse(module.linkedBuildRoot().toAbsolutePath().normalize().toString()),
            gradleProjectPath = AdditionGradleProjectPath.parse(module.gradleProjectPath()),
            sourceSetName = AdditionGradleSourceSetName.of(sourceSet.sourceSetName()),
        ),
        modelFingerprint = AdditionProjectModelFingerprint.of(projectModelFingerprint(model)),
        classpathFingerprint = AdditionClasspathFingerprint.of(
            BuildClasspathFingerprintResolver.resolve(project, sharedWorkspaceIdentity).value,
        ),
        sourceFiles = sourceFiles,
        anchorSourceFiles = anchorSourceFiles,
    )
}

private fun sourceFilesUnder(root: Path): List<Path> {
    if (Files.isSymbolicLink(root)) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "A model-owned source root must not be a symbolic link",
    )
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return emptyList()
    return Files.walk(root).use { paths ->
        val entries = paths.toList()
        if (entries.any(Files::isSymbolicLink)) failAddition(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            "Model-owned Kotlin and Java source context must not contain symbolic links",
        )
        entries.asSequence().filter { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) }
            .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java") }
            .map { it.toAbsolutePath().normalize() }
            .toList()
    }
}

private fun projectModelFingerprint(model: io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleWorkspaceModel): String =
    FileHashing.sha256(
        buildString {
            append("complete=").append(model.importedModelComplete()).append('\n')
            model.moduleAssociations().sortedWith(
                compareBy(
                    { it.ideaModuleName() },
                    { it.linkedBuildRoot().toAbsolutePath().normalize().toString() },
                    { it.gradleProjectPath() },
                ),
            ).forEach { module ->
                append(module.ideaModuleName()).append('|')
                    .append(module.linkedBuildRoot().toAbsolutePath().normalize()).append('|')
                    .append(module.gradleProjectPath()).append('\n')
                module.sourceSets().sortedBy { it.sourceSetName() }.forEach { sourceSet ->
                    append(sourceSet.sourceSetName()).append('|')
                    append(sourceSet.sourceRoots().map { it.toAbsolutePath().normalize().toString() }.sorted())
                    append('\n')
                }
            }
        },
    )

private fun KastIndexerBackend.exactAdditionContext(
    owner: AdditionOwnerSnapshot,
    generation: Long,
    targetToInclude: Path?,
): ExactAdditionProofContext {
    val files = (owner.sourceFiles + listOfNotNull(targetToInclude)).distinct().sortedBy(Path::toString)
    return ExactAdditionProofContext.of(
        requiredGeneration = MutationSemanticGeneration(generation),
        projectModelFingerprint = owner.modelFingerprint,
        classpathFingerprint = owner.classpathFingerprint,
        contextFileHashes = files.map { path ->
            ExactAdditionContextFileHash.of(path.toString(), FileHashing.sha256(secureAdditionRead(path)))
        },
    )
}

private fun KastIndexerBackend.revalidateAdditionContext(
    owner: AdditionOwnerSnapshot,
    generation: Long,
    context: ExactAdditionProofContext,
    target: Path,
    mustExist: Boolean,
) {
    if (psiGeneration() != generation) failAddition(
        AdditionProofLimitation.GENERATION_CHANGED,
        "The semantic generation changed during addition planning",
    )
    val currentOwner = exactAdditionOwner(target)
    if (currentOwner.modelFingerprint != owner.modelFingerprint) failAddition(
        AdditionProofLimitation.PROJECT_MODEL_CHANGED,
        "The Gradle project model changed during addition planning",
    )
    if (currentOwner.classpathFingerprint != owner.classpathFingerprint) failAddition(
        AdditionProofLimitation.CLASSPATH_CHANGED,
        "The compiler classpath changed during addition planning",
    )
    if (currentOwner.sourceFiles != owner.sourceFiles) failAddition(
        AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
        "The model-owned Kotlin and Java source-file set changed during addition planning",
    )
    if (Files.exists(target, NOFOLLOW_LINKS) != mustExist) failAddition(
        if (mustExist) AdditionProofLimitation.TARGET_FILE_MISSING else AdditionProofLimitation.TARGET_ALREADY_EXISTS,
        "The addition target state changed during planning",
    )
    context.contextFileHashes.forEach { expected ->
        val path = Path.of(expected.filePath)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || FileHashing.sha256(secureAdditionRead(path)) != expected.sha256) {
            failAddition(
                AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
                "A compiler source-context file changed during addition planning",
            )
        }
    }
    if (psiGeneration() != generation) failAddition(
        AdditionProofLimitation.GENERATION_CHANGED,
        "The semantic generation changed during addition proof finalization",
    )
}

private fun KastIndexerBackend.findKtFileOrNull(path: Path): KtFile? {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
}

private fun KastIndexerBackend.secureAdditionRead(path: Path): ByteArray = try {
    exactFileImageMutation.readFileBytes(path, IdeaWorkspaceMutation.TEXT_EDIT)
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    failAddition(
        AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
        "An exact source-context image could not be read without following symbolic links",
    )
}

private fun requireSecureAbsentTarget(target: Path, owner: AdditionSourceOwner) {
    val normalizedTarget = target.toAbsolutePath().normalize()
    val normalizedParent = normalizedTarget.parent ?: failAddition(
        AdditionProofLimitation.TARGET_PARENT_MISSING,
        "The add-file target has no parent directory",
    )
    val canonicalParent = try {
        normalizedParent.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.TARGET_PARENT_MISSING, "The add-file parent is not canonical")
    }
    val sourceRoot = Path.of(owner.sourceRoot.value)
    val canonicalSourceRoot = try {
        sourceRoot.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.SOURCE_OWNER_UNPROVEN, "The model-owned source root is not canonical")
    }
    val canonicalCandidate = canonicalParent.resolve(normalizedTarget.fileName).normalize()
    if (canonicalParent != normalizedParent || canonicalSourceRoot != sourceRoot ||
        canonicalCandidate != normalizedTarget || !canonicalCandidate.startsWith(canonicalSourceRoot)
    ) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The add-file target or its parent escapes the canonical model-owned source root",
    )
}

private fun requireSecureExistingTarget(target: Path, owner: AdditionSourceOwner) {
    val normalizedTarget = target.toAbsolutePath().normalize()
    val canonicalTarget = try {
        normalizedTarget.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.TARGET_NOT_KOTLIN_SOURCE, "The target path is not canonical")
    }
    val sourceRoot = Path.of(owner.sourceRoot.value)
    val canonicalSourceRoot = try {
        sourceRoot.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.SOURCE_OWNER_UNPROVEN, "The model-owned source root is not canonical")
    }
    if (canonicalTarget != normalizedTarget || canonicalSourceRoot != sourceRoot ||
        !canonicalTarget.startsWith(canonicalSourceRoot)
    ) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The add-declaration target escapes the canonical model-owned source root",
    )
}

private fun strictAdditionPlannerUtf8Bytes(value: String): ByteArray {
    val encoded = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(value))
    return ByteArray(encoded.remaining()).also(encoded::get)
}

internal fun org.jetbrains.kotlin.name.FqName.toAdditionPackage(): AdditionKotlinPackage =
    if (isRoot) AdditionKotlinPackage.Root else AdditionKotlinPackage.Named.of(*pathSegments().map { it.asString() }.toTypedArray())

private fun AdditionTargetPath.toJavaPath(): Path = Path.of(value)

private fun failAddition(limitation: AdditionProofLimitation, message: String): Nothing =
    throw AdditionProofIncompleteException.of(limitation, message = message)

private val KOTLIN_CONVENTION_NAMES = setOf(
    "compareTo", "component1", "component2", "component3", "component4", "component5",
    "contains", "dec", "div", "divAssign", "equals", "get", "hasNext", "inc", "invoke",
    "iterator", "minus", "minusAssign", "mod", "modAssign", "next", "not", "plus", "plusAssign",
    "provideDelegate", "rangeTo", "rangeUntil", "rem", "remAssign", "set", "setValue", "times",
    "timesAssign", "unaryMinus", "unaryPlus", "getValue",
)
