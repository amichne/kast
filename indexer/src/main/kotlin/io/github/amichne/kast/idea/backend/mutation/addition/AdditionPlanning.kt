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
    val editableTarget: EditableAdditionTarget,
    val owner: AdditionSourceOwner,
    val modelFingerprint: AdditionProjectModelFingerprint,
    val classpathFingerprint: AdditionClasspathFingerprint,
    val sourceFiles: List<AdditionProofFile>,
    val anchorSourceFiles: List<AdditionProofFile>,
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

/**
 * Proof transition: `ParsedAddFilePlanQuery -> AddFilePlanResult`.
 *
 * Establishes an authored exact source owner, an absent canonical target, compiler-valid content,
 * collision/rebinding evidence, and a revalidated proof context. Expected planning failures are
 * closed by `AdditionProofIncompleteException` and `AdditionProofLimitation`; raw bytes are
 * extracted only at the exact text-image planner boundary.
 */
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
            val creatableTarget = CreatableAdditionTarget.admit(owner.editableTarget)
            val anchor = owner.anchorSourceFiles.asSequence()
                .map(AdditionProofFile::path)
                .filter { it.toString().endsWith(".kt") }
                .mapNotNull(::findKtFileOrNull)
                .firstOrNull()
                ?: failAddition(
                    AdditionProofLimitation.MODULE_CONTEXT_ANCHOR_UNAVAILABLE,
                    "The exact source owner has no Kotlin module-context anchor",
                )
            val parsed = parseAddFile(query.proposedContent.value, target.fileName.toString(), anchor)
            proveZeroRebindingCandidates(
                parsed.declarations,
                parsed.packageIdentity,
                owner.sourceFiles.map(AdditionProofFile::path),
            )
            val proofParts = proveAdditionDeclarations(parsed, relativeBaseOffset = 0)
            val generation = psiGeneration()
            val context = exactAdditionContext(owner, generation)
            val revalidatedContext = RevalidatedAdditionContext.admit(
                backend = this@planAddFileOperation,
                owner = owner,
                generation = generation,
                context = context,
                target = creatableTarget,
            )
            val postimage = strictAdditionPlannerUtf8Bytes(query.proposedContent.value)
            AddFilePlanResult.of(
                proposedContent = query.proposedContent.value,
                proof = ExactAddFileProof.of(
                    targetPath = query.targetPath,
                    owner = owner.owner,
                    packageIdentity = parsed.packageIdentity,
                    declarations = proofParts.declarations,
                    context = revalidatedContext.context,
                    collisionEvidence = ExactAdditionCollisionEvidence.complete(proofParts.declarations.size),
                    outboundEvidence = proofParts.outbound,
                    rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
                    postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(postimage)),
                ),
            )
        }
    }
}

/**
 * Proof transition: `ParsedAddDeclarationPlanQuery -> AddDeclarationPlanResult`.
 *
 * Establishes an authored exact source owner, canonical existing target and preimage, compiler-valid
 * insertion, collision/rebinding evidence, and a revalidated proof context. Expected planning
 * failures are closed by `AdditionProofIncompleteException` and `AdditionProofLimitation`; raw
 * bytes are extracted only at the exact text-image planner boundary.
 */
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
            val owner = exactAdditionOwner(target)
            val existingTarget = ExistingAdditionTarget.admit(
                this@planAddDeclarationOperation,
                owner.editableTarget,
            )
            val rawPreimage = existingTarget.copyPreimage()
            val actualPrehash = FileHashing.sha256(rawPreimage)
            if (actualPrehash != query.expectedCurrentSha256.value) failAddition(
                AdditionProofLimitation.TARGET_FILE_HASH_CHANGED,
                "The exact add-declaration target preimage changed",
            )
            val targetFile = findKtFileOrNull(target) ?: failAddition(
                AdditionProofLimitation.TARGET_NOT_KOTLIN_SOURCE,
                "The add-declaration target is not one exact Kotlin source file",
            )
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
            proveZeroRebindingCandidates(
                contextual.declarations,
                contextual.packageIdentity,
                owner.sourceFiles.map(AdditionProofFile::path),
            )
            val proofParts = proveAdditionDeclarations(contextual, relativeBaseOffset = proposedStart)
            val generation = psiGeneration()
            val context = exactAdditionContext(owner, generation)
            val revalidatedContext = RevalidatedAdditionContext.admit(
                backend = this@planAddDeclarationOperation,
                owner = owner,
                generation = generation,
                context = context,
                target = existingTarget,
            )
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
                    context = revalidatedContext.context,
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
