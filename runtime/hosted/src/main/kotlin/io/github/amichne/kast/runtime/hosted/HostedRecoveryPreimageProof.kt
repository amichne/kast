package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.recovery.RecoveryDocumentObservation
import io.github.amichne.kast.change.recovery.RecoverySourceObservation
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference

internal data class HostedRecoveryExpectation(
    val plan: LiveAddDeclarationChangePlan,
    val before: LiveSemanticReadReference,
    val record: MutationRecoveryRecord,
)

internal enum class HostedRecoveryProofFailure {
    OWNER_CHANGED,
    MODEL_CHANGED,
    REFERENCE_REGRESSED,
    RECORD_MISMATCH,
    PHYSICAL_PREIMAGE_MISMATCH,
    DOCUMENT_PREIMAGE_MISMATCH,
    DOCUMENT_NOT_READY,
}

/** Detached correlation proof only; the caller must obtain after through a completed fresh native read. */
internal class HostedRecoveryPreimageProof private constructor(val after: LiveChangeBasis) {
    companion object {
        fun admit(
            expected: HostedRecoveryExpectation,
            after: LiveChangeBasis,
            observed: RecoverySourceObservation,
        ): Refinement<HostedRecoveryPreimageProof, HostedRecoveryProofFailure> {
            val reference = after.reference
            if (reference.host != expected.before.host) return rejected(HostedRecoveryProofFailure.OWNER_CHANGED)
            if (
                reference.epoch.value < expected.before.epoch.value ||
                    reference.contentView != expected.before.contentView
            ) {
                return rejected(HostedRecoveryProofFailure.REFERENCE_REGRESSED)
            }
            val original = expected.plan.basis.observation
            if (
                reference.workspaceRoot != expected.before.workspaceRoot ||
                    reference.workspaceRoot != original.reference.workspaceRoot ||
                    after.model.sourceRoots != original.model.sourceRoots
            ) {
                return rejected(HostedRecoveryProofFailure.MODEL_CHANGED)
            }
            return admitImages(expected, after, observed)
        }

        private fun admitImages(
            expected: HostedRecoveryExpectation,
            after: LiveChangeBasis,
            observed: RecoverySourceObservation,
        ): Refinement<HostedRecoveryPreimageProof, HostedRecoveryProofFailure> {
            val write =
                expected.record.preparation.plannedWrites.singleOrNull()
                    ?: return rejected(HostedRecoveryProofFailure.RECORD_MISMATCH)
            if (
                expected.record.binding.value != expected.plan.planId.value ||
                    write.source.value != expected.plan.target.file.path.value ||
                    write.preimage.digest.value != expected.plan.content.value
            ) {
                return rejected(HostedRecoveryProofFailure.RECORD_MISMATCH)
            }
            if (observed.source != write.source) return rejected(HostedRecoveryProofFailure.RECORD_MISMATCH)
            if (observed.savedContent != write.preimage) {
                return rejected(HostedRecoveryProofFailure.PHYSICAL_PREIMAGE_MISMATCH)
            }
            when (val document = observed.document) {
                is RecoveryDocumentObservation.SavedAndCommitted ->
                    if (document.content != write.preimage) {
                        return rejected(HostedRecoveryProofFailure.DOCUMENT_PREIMAGE_MISMATCH)
                    }
                RecoveryDocumentObservation.NotLoaded,
                RecoveryDocumentObservation.DirtyOrUncommitted,
                RecoveryDocumentObservation.Unavailable ->
                    return rejected(HostedRecoveryProofFailure.DOCUMENT_NOT_READY)
            }
            return Refinement.Refined(HostedRecoveryPreimageProof(after))
        }

        private fun rejected(failure: HostedRecoveryProofFailure) = Refinement.Rejected(failure)
    }
}
