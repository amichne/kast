package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class LiveRecoveryAdmissionFailure {
    APPROVAL_MISMATCH,
    ROOT_MISMATCH,
    MODEL_MOVED,
    RECORD_MISMATCH,
    PREIMAGE_INVALID,
    POSTIMAGE_INVALID,
}

/** Recovery-only exact images. A new owner can restore this source but cannot execute the old plan. */
class LiveRecoveryAuthority
private constructor(
    val plan: LiveAddDeclarationChangePlan,
    val approval: VerifiedLivePlanApproval,
    val current: LiveSemanticReadReference,
    val record: MutationRecoveryRecord,
    private val preimage: ObservedMutationSource,
    private val postimage: DerivedMutationPostimage,
) {
    val expectedPostimage: WorkspaceSourceContentHash
        get() = postimage.content

    fun preimageTextAtIntellijBoundary(): String = preimage.text

    fun postimageTextAtIntellijBoundary(): String = postimage.text

    fun preimageBytesAtRecoveryBoundary(): ByteArray = preimage.recoveryPreimage.decodeAtRecoveryBoundary()

    fun expectedRecoveryPostimage(): RecoveryPreimage =
        RecoveryPreimage.fromBoundary(postimage.text.toByteArray(Charsets.UTF_8))

    companion object {
        fun admit(
            plan: LiveAddDeclarationChangePlan,
            approval: VerifiedLivePlanApproval,
            current: LiveSemanticReadAuthority,
            model: WorkspaceSearchScopeModel,
            record: MutationRecoveryRecord,
        ): Refinement<LiveRecoveryAuthority, LiveRecoveryAdmissionFailure> {
            if (
                plan.basis.observation.reference.workspaceRoot != current.workspaceRoot ||
                    model.workspaceRoot != current.workspaceRoot
            ) {
                return rejected(LiveRecoveryAdmissionFailure.ROOT_MISMATCH)
            }
            if (model.sourceRoots != plan.basis.observation.model.sourceRoots)
                return rejected(LiveRecoveryAdmissionFailure.MODEL_MOVED)
            if (
                approval.operation != LiveChangeEffect.CHANGE_RECOVER ||
                    approval.owner != current.reference.host ||
                    approval.root != current.workspaceRoot
            ) {
                return rejected(LiveRecoveryAdmissionFailure.APPROVAL_MISMATCH)
            }
            if (approval.planId != plan.planId) return rejected(LiveRecoveryAdmissionFailure.APPROVAL_MISMATCH)
            val preimage =
                when (val observed = observePreimage(plan, record)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected -> return observed
                }
            val postimage =
                when (val derived = DerivedMutationPostimage.derive(preimage, plan.writes.entries.single().mutations)) {
                    is Refinement.Refined -> derived.value
                    is Refinement.Rejected -> return rejected(LiveRecoveryAdmissionFailure.POSTIMAGE_INVALID)
                }
            return Refinement.Refined(
                LiveRecoveryAuthority(
                    plan = plan,
                    approval = approval,
                    current = current.reference,
                    record = record,
                    preimage = preimage,
                    postimage = postimage,
                )
            )
        }

        private fun observePreimage(
            plan: LiveAddDeclarationChangePlan,
            record: MutationRecoveryRecord,
        ): Refinement<ObservedMutationSource, LiveRecoveryAdmissionFailure> {
            val write =
                record.preparation.plannedWrites.singleOrNull()
                    ?: return rejected(LiveRecoveryAdmissionFailure.RECORD_MISMATCH)
            if (
                record.binding.value != plan.planId.value ||
                    write.source.value != plan.target.file.path.value ||
                    write.preimage.digest.value != plan.content.value
            )
                return rejected(LiveRecoveryAdmissionFailure.RECORD_MISMATCH)
            val preimage =
                when (
                    val captured =
                        ObservedMutationSource.capture(
                            plan.target.file,
                            write.preimage.decodeAtRecoveryBoundary(),
                            SourceWriteAccess.Writable,
                        )
                ) {
                    is Refinement.Refined -> captured.value
                    is Refinement.Rejected -> return rejected(LiveRecoveryAdmissionFailure.PREIMAGE_INVALID)
                }
            return Refinement.Refined(preimage)
        }

        private fun rejected(failure: LiveRecoveryAdmissionFailure) = Refinement.Rejected(failure)
    }
}
