package io.github.amichne.kast.idea.backend.mutation

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementBodySha256
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedReplacementPlanQuery
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.shared.analysis.toKastLocation
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Proof transition: [ReplacementPlanningSnapshot] plus the exact preimage hash ->
 * [ReplacementAdmission] of [ExactReplacementProof].
 *
 * Establishes that generation, compiler context, source identity, and original body-write authority
 * remained unchanged through proof construction, then consumes the host-neutral
 * [ExactReplacementProof.admit] transition. Failure is a closed [ReplacementProofRejection]. Raw
 * fields may be extracted only at the indexer proof-emission boundary.
 */
internal fun KastIndexerBackend.finalizeReplacementProof(
    query: ParsedReplacementPlanQuery,
    snapshot: ReplacementPlanningSnapshot,
    fileHashes: List<FileHash>,
): ReplacementAdmission<ExactReplacementProof> {
    val occurrenceCount = snapshot.outboundReferences.size
    if (psiGeneration() != snapshot.generation) {
        return replacementRejection(
            ReplacementProofLimitation.GENERATION_CHANGED,
            "The semantic generation changed before replacement proof finalization",
            occurrenceCount,
        )
    }
    val file = findKtFile(snapshot.target.declarationFile.value)
    val currentContextHash = FileHashing.sha256(file.text)
    if (
        currentContextHash != snapshot.sourceContextHash ||
        fileHashes.size != 1 ||
        fileHashes.single().filePath != snapshot.target.declarationFile.value
    ) {
        return replacementRejection(
            ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED,
            "The exact source context changed while the replacement proof was being built",
            occurrenceCount,
        )
    }
    val currentCompilerContext = when (
        val observation = observeReplacementCompilerContext(
            ExactFileImagePath(snapshot.target.declarationFile.value),
        )
    ) {
        is ReplacementCompilerContextObservation.Proven -> observation.context
        is ReplacementCompilerContextObservation.Rejected -> return replacementRejection(
            ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED,
            "Replacement compiler context could not be re-observed: ${observation.failure.name}",
            occurrenceCount,
        )
    }
    if (currentCompilerContext != snapshot.compilerContext) {
        return replacementRejection(
            ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED,
            "The unchanged compiler context changed while the replacement proof was being built",
            occurrenceCount,
        )
    }
    val targets = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset == snapshot.target.declarationStartOffset.value
        }
    if (targets.size != 1) {
        return replacementRejection(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target disappeared before proof finalization",
            occurrenceCount,
        )
    }
    val target = targets.single()
    val targetBody = when (target) {
        is KtNamedFunction -> target.bodyExpression
        else -> null
    }
    if (
        targetBody == null ||
        compilerSourceIdentity(target) != snapshot.target ||
        target.toKastLocation(targetBody.textRange) != snapshot.sourceRange
    ) {
        return replacementRejection(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target changed before proof finalization",
            occurrenceCount,
        )
    }
    if (psiGeneration() != snapshot.generation) {
        return replacementRejection(
            ReplacementProofLimitation.GENERATION_CHANGED,
            "The semantic generation changed during replacement proof finalization",
            occurrenceCount,
        )
    }
    val declarationHash = when (
        val admission = ReplacementDeclarationSha256.parse(
            FileHashing.sha256(query.proposedDeclaration.value),
        )
    ) {
        is ReplacementContractAdmission.Admitted -> admission.value
        is ReplacementContractAdmission.Rejected -> return replacementRejection(
            ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
            "The submitted declaration hash could not be admitted",
            occurrenceCount,
        )
    }
    val bodyHash = when (
        val admission = ReplacementBodySha256.parse(FileHashing.sha256(snapshot.proposedBodyText))
    ) {
        is ReplacementContractAdmission.Admitted -> admission.value
        is ReplacementContractAdmission.Rejected -> return replacementRejection(
            ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
            "The replacement body hash could not be admitted",
            occurrenceCount,
        )
    }
    return when (
        val admission = ExactReplacementProof.admit(
            target = snapshot.target,
            requiredGeneration = MutationSemanticGeneration(snapshot.generation),
            sourceRange = snapshot.sourceRange,
            fileHashes = fileHashes,
            compilerContext = snapshot.compilerContext,
            oldSignature = snapshot.oldSignature,
            proposedSignature = snapshot.proposedSignature,
            proposedDeclarationHash = declarationHash,
            proposedDeclarationLength = query.proposedDeclaration.value.length,
            proposedBodyHash = bodyHash,
            proposedBodyLength = snapshot.proposedBodyText.length,
            declarationSlice = snapshot.declarationSlice,
            proposedBodySlice = snapshot.proposedBodySlice,
            evidence = ReplacementOutboundEvidence.Complete.of(occurrenceCount),
            outboundReferences = snapshot.outboundReferences,
        )
    ) {
        is ReplacementContractAdmission.Admitted -> ReplacementAdmission.Admitted(admission.value)
        is ReplacementContractAdmission.Rejected -> replacementRejection(
            ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
            "The exact replacement proof contract rejected ${admission.failure.name}",
            occurrenceCount,
        )
    }
}
