@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionAuthority
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.CompleteRelationshipCoverageAdmission
import io.github.amichne.kast.idea.backend.relationships.completeRelationshipCoverageAdmission
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.visibility
import java.nio.file.Path
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal fun KastIndexerBackend.verifyAddFile(
    authority: ParsedMutationPostconditionAuthority.AddFile,
): MutationPostconditionEvidence.AddFile = verifyAddition(
    targetPath = authority.proof.targetPath.value,
    expectedOwner = authority.proof.owner,
    expectedContext = authority.proof.context,
    expectedPackage = authority.proof.packageIdentity,
    expectedDeclarations = authority.proof.declarations,
    expectedOutbound = authority.proof.outboundEvidence,
    relativeBaseOffset = 0,
    expectedContextPaths = authority.proof.context.contextFileHashes.map { it.filePath }.toSet() +
        authority.proof.targetPath.value,
    targetContextMayDiffer = true,
    provenFileBottomOffset = null,
).let { parts ->
    MutationPostconditionEvidence.AddFile(
        owner = authority.proof.owner,
        packageIdentity = authority.proof.packageIdentity,
        declarations = parts.declarations,
        outboundEvidence = parts.outbound,
    )
}

internal fun KastIndexerBackend.verifyAddDeclaration(
    authority: ParsedMutationPostconditionAuthority.AddDeclaration,
): MutationPostconditionEvidence.AddDeclaration {
    val normalizedPreimage = authority.image.preimage.copyBytes().toString(Charsets.UTF_8)
        .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val separatorLength = when {
        normalizedPreimage.isEmpty() || normalizedPreimage.endsWith("\n\n") -> 0
        normalizedPreimage.endsWith('\n') -> 1
        else -> 2
    }
    val relativeBase = authority.proof.insertion.offset.value + separatorLength
    val parts = verifyAddition(
        targetPath = authority.proof.targetPath.value,
        expectedOwner = authority.proof.owner,
        expectedContext = authority.proof.context,
        expectedPackage = authority.proof.packageIdentity,
        expectedDeclarations = listOf(authority.proof.declaration),
        expectedOutbound = authority.proof.outboundEvidence,
        relativeBaseOffset = relativeBase,
        expectedContextPaths = authority.proof.context.contextFileHashes.map { it.filePath }.toSet(),
        targetContextMayDiffer = true,
        provenFileBottomOffset = authority.proof.insertion.offset.value,
    )
    return MutationPostconditionEvidence.AddDeclaration(
        owner = authority.proof.owner,
        packageIdentity = authority.proof.packageIdentity,
        declaration = parts.declarations.single(),
        outboundEvidence = parts.outbound,
    )
}

private fun KastIndexerBackend.verifyAddition(
    targetPath: String,
    expectedOwner: AdditionSourceOwner,
    expectedContext: ExactAdditionProofContext,
    expectedPackage: AdditionKotlinPackage,
    expectedDeclarations: List<AdditionTopLevelDeclaration>,
    expectedOutbound: ExactAdditionOutboundEvidence,
    relativeBaseOffset: Int,
    expectedContextPaths: Set<String>,
    targetContextMayDiffer: Boolean,
    provenFileBottomOffset: Int?,
): AdditionProofParts {
    val target = Path.of(targetPath)
    val currentOwner = try {
        exactAdditionOwner(target)
    } catch (failure: AdditionProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.SOURCE_OWNER_CHANGED,
            "The addition source owner can no longer be proven",
        )
    }
    if (currentOwner.owner != expectedOwner) failPostcondition(
        MutationPostconditionLimitation.SOURCE_OWNER_CHANGED,
        "The exact addition source owner changed",
    )
    if (currentOwner.modelFingerprint != expectedContext.projectModelFingerprint) failPostcondition(
        MutationPostconditionLimitation.PROJECT_MODEL_CHANGED,
        "The exact addition Gradle project model changed",
    )
    if (currentOwner.classpathFingerprint != expectedContext.classpathFingerprint) failPostcondition(
        MutationPostconditionLimitation.CLASSPATH_CHANGED,
        "The exact addition compiler classpath changed",
    )
    val currentPaths = currentOwner.sourceFiles.map { it.path.toString() }.toSet()
    if (currentPaths != expectedContextPaths) failPostcondition(
        MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
        "The exact model-owned source file set changed",
    )
    val currentFiles = currentOwner.sourceFiles.associateBy { it.path.toString() }
    expectedContext.contextFileHashes.forEach { expected ->
        if (targetContextMayDiffer && expected.filePath == targetPath) return@forEach
        val sourceFile = currentFiles[expected.filePath] ?: failPostcondition(
            MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
            "A required addition source-context file is no longer model-owned",
        )
        val actual = try {
            sourceFile.readExactBytes()
        } catch (_: AdditionProofIncompleteException) {
            failPostcondition(
                MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
                "A required addition source-context image is unreadable",
            )
        }
        if (FileHashing.sha256(actual) != expected.sha256) failPostcondition(
            MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
            "A non-target addition source-context image changed",
        )
    }
    val file = exactPostimageKtFile(targetPath)
    val declarations = if (provenFileBottomOffset == null) {
        file.declarations.map { declaration -> declaration as? KtNamedDeclaration ?: failPostcondition(
            MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
            "The added file contains an unsupported top-level declaration",
        ) }
    } else {
        exactFileBottomDeclaration(file, provenFileBottomOffset, relativeBaseOffset)
    }
    if (declarations.isEmpty()) failPostcondition(
        MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
        "The resulting addition declaration was not found at its authorized source range",
    )
    val parsed = ParsedAddition(
        file,
        declarations,
        file.packageFqName.toAdditionPackage(),
        AdditionAnalysisSource.PROJECT_POSTIMAGE,
    )
    val proofParts = try {
        proveAdditionDeclarations(parsed, relativeBaseOffset)
    } catch (failure: AdditionProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
            "The resulting addition declaration or outbound proof is incomplete: ${failure.message}",
        )
    }
    if (parsed.packageIdentity != expectedPackage || proofParts.declarations != expectedDeclarations) failPostcondition(
        MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
        "The resulting addition declaration identities changed",
    )
    if (proofParts.outbound != expectedOutbound) failPostcondition(
        MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
        "The resulting addition outbound occurrence set changed",
    )
    val exclusions = mapOf(
        targetPath to declarations.map { declaration ->
            declaration.textRange.startOffset until declaration.textRange.endOffset
        },
    )
    try {
        proveZeroRebindingCandidates(
            declarations,
            expectedPackage,
            currentOwner.sourceFiles.map { it.path },
            exclusions,
        )
    } catch (failure: AdditionProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.COLLISION_OR_REBINDING_CHANGED,
            "The resulting addition has a new collision or rebinding candidate",
        )
    }
    return proofParts
}

internal fun exactFileBottomDeclaration(
    file: KtFile,
    provenFileBottomOffset: Int,
    relativeBaseOffset: Int,
): List<KtNamedDeclaration> {
    val appendedSyntax = file.children.filter { child ->
        child.textRange.endOffset > provenFileBottomOffset && child !is PsiWhiteSpace
    }
    val declaration = appendedSyntax.singleOrNull() as? KtNamedDeclaration
        ?: failPostcondition(
            MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
            "The FILE_BOTTOM append must contain exactly one supported top-level declaration",
        )
    if (declaration.textRange.startOffset != relativeBaseOffset) failPostcondition(
        MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
        "The FILE_BOTTOM declaration does not start at the exact authorized offset",
    )
    return listOf(declaration)
}
