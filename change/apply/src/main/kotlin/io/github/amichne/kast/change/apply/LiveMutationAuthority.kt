package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangeApplicationClaim
import io.github.amichne.kast.change.contract.LiveChangeApplicationStore
import io.github.amichne.kast.change.contract.LiveChangeBasisComparison
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryPreparation
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.recovery.PreparedAddDeclarationRecovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class LiveMutationAdmissionFailure {
    BASIS_MOVED,
    APPROVAL_MISMATCH,
    SOURCE_MISMATCH,
    READ_ONLY,
    POSTIMAGE_INVALID,
    RECOVERY_INVALID,
    RECOVERY_UNAVAILABLE,
    ATTEMPT_UNAVAILABLE,
}

sealed interface LiveMutationPreparation {
    data class Ready(val authority: LiveMutationAuthority) : LiveMutationPreparation

    data object AlreadyAttempted : LiveMutationPreparation

    data object ClaimedWithoutRecovery : LiveMutationPreparation

    data class Rejected(val failure: LiveMutationAdmissionFailure) : LiveMutationPreparation
}

data class LiveMutationCandidate(
    val plan: LiveAddDeclarationChangePlan,
    val current: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val observed: ObservedMutationSource,
    val approval: VerifiedLivePlanApproval,
)

/** A fresh admitted candidate plus exact approval, permanent attempt claim, and durable preimage. */
class LiveMutationAuthority
private constructor(
    val plan: LiveAddDeclarationChangePlan,
    val approval: VerifiedLivePlanApproval,
    val recovery: PreparedAddDeclarationRecovery,
    private val preimage: ObservedMutationSource,
    private val postimage: DerivedMutationPostimage,
) {
    val expectedPostimage: WorkspaceSourceContentHash
        get() = postimage.content

    val source
        get() = plan.target.file

    fun preimageTextAtIntellijBoundary(): String = preimage.text

    fun postimageTextAtIntellijBoundary(): String = postimage.text

    fun mutationsAtIntellijBoundary(): List<SourceTextMutation> = postimage.mutations

    companion object {
        fun prepare(
            candidate: LiveMutationCandidate,
            attempts: LiveChangeApplicationStore,
            recovery: AddDeclarationRecoveryService,
        ): LiveMutationPreparation {
            val admitted =
                when (val result = admitCandidate(candidate)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return rejected(result.failure)
                }
            val (identity, preparation, postimage) = admitted
            val plan = candidate.plan
            val observed = candidate.observed
            val approval = candidate.approval
            when (attempts.claimApplication(identity)) {
                is LiveChangeApplicationClaim.Claimed -> Unit
                LiveChangeApplicationClaim.AlreadyAttempted -> return LiveMutationPreparation.AlreadyAttempted
                LiveChangeApplicationClaim.Missing,
                is LiveChangeApplicationClaim.Rejected ->
                    return rejected(LiveMutationAdmissionFailure.ATTEMPT_UNAVAILABLE)
            }
            val prepared =
                when (val result = recovery.prepare(preparation)) {
                    is PrepareAddDeclarationRecoveryResult.Prepared -> result.recovery
                    is PrepareAddDeclarationRecoveryResult.Rejected ->
                        return LiveMutationPreparation.ClaimedWithoutRecovery
                }
            return LiveMutationPreparation.Ready(
                LiveMutationAuthority(
                    plan = plan,
                    approval = approval,
                    recovery = prepared,
                    preimage = observed,
                    postimage = postimage,
                )
            )
        }

        private data class AdmittedCandidate(
            val identity: ChangePlanIdentity,
            val preparation: AddDeclarationRecoveryPreparation,
            val postimage: DerivedMutationPostimage,
        )

        private fun admitCandidate(
            candidate: LiveMutationCandidate
        ): Refinement<AdmittedCandidate, LiveMutationAdmissionFailure> {
            val plan = candidate.plan
            val current = candidate.current
            val model = candidate.model
            val observed = candidate.observed
            val approval = candidate.approval
            if (plan.basis.observation.compare(current.reference, model) != LiveChangeBasisComparison.UNCHANGED) {
                return Refinement.Rejected(LiveMutationAdmissionFailure.BASIS_MOVED)
            }
            if (approval.operation != LiveChangeEffect.CHANGE_APPLY)
                return Refinement.Rejected(LiveMutationAdmissionFailure.APPROVAL_MISMATCH)
            if (
                approval.planId != plan.planId ||
                    approval.root != current.workspaceRoot ||
                    approval.owner != current.reference.host
            ) {
                return Refinement.Rejected(LiveMutationAdmissionFailure.APPROVAL_MISMATCH)
            }

            if (observed.source != plan.target.file || observed.content != plan.content) {
                return Refinement.Rejected(LiveMutationAdmissionFailure.SOURCE_MISMATCH)
            }
            if (observed.access != SourceWriteAccess.Writable)
                return Refinement.Rejected(LiveMutationAdmissionFailure.READ_ONLY)
            val write =
                plan.writes.entries.singleOrNull()
                    ?: return Refinement.Rejected(LiveMutationAdmissionFailure.SOURCE_MISMATCH)
            val postimage =
                when (val derived = DerivedMutationPostimage.derive(observed, write.mutations)) {
                    is Refinement.Refined -> derived.value
                    is Refinement.Rejected -> return Refinement.Rejected(LiveMutationAdmissionFailure.POSTIMAGE_INVALID)
                }
            val preparation =
                when (val admitted = AddDeclarationRecoveryPreparation.fromPlan(plan, observed.recoveryPreimage)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return Refinement.Rejected(LiveMutationAdmissionFailure.RECOVERY_INVALID)
                }
            val identity = checkNotNull(ChangePlanIdentity.parse("plan:${plan.planId.value}"))
            return Refinement.Refined(AdmittedCandidate(identity, preparation, postimage))
        }

        private fun rejected(failure: LiveMutationAdmissionFailure) = LiveMutationPreparation.Rejected(failure)
    }
}

/** Exact singleton postimage observed after durability and save, retaining its admitted application. */
class LiveAppliedSourceWrite
private constructor(val authority: LiveMutationAuthority, val content: WorkspaceSourceContentHash) {
    companion object {
        fun observe(
            authority: LiveMutationAuthority,
            bytes: ByteArray,
            paths: Set<String>,
        ): Refinement<LiveAppliedSourceWrite, AppliedSourceWriteFailure> {
            if (paths != setOf(authority.source.path.value))
                return Refinement.Rejected(AppliedSourceWriteFailure.CHANGED_WRITE_SET_MISMATCH)
            val observed =
                when (
                    val captured = ObservedMutationSource.capture(authority.source, bytes, SourceWriteAccess.Writable)
                ) {
                    is Refinement.Refined -> captured.value
                    is Refinement.Rejected -> return Refinement.Rejected(AppliedSourceWriteFailure.INVALID_CONTENT)
                }
            if (
                observed.content != authority.expectedPostimage ||
                    observed.text != authority.postimageTextAtIntellijBoundary()
            ) {
                return Refinement.Rejected(AppliedSourceWriteFailure.POSTIMAGE_MISMATCH)
            }
            return Refinement.Refined(LiveAppliedSourceWrite(authority, observed.content))
        }
    }
}

sealed interface LiveSourceWriteResult {
    data class Applied(val write: LiveAppliedSourceWrite) : LiveSourceWriteResult

    data class RejectedBeforeMutation(val failure: SourceWriteFailure) : LiveSourceWriteResult

    data class RejectedAfterRollback(val failure: SourceWriteFailure) : LiveSourceWriteResult

    data class RecoveryRequired(val failure: SourceWriteFailure) : LiveSourceWriteResult
}
