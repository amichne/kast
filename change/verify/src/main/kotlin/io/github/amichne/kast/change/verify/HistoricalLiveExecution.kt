package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.apply.LiveMutationAuthority
import io.github.amichne.kast.change.contract.ChangePlanId
import io.github.amichne.kast.change.contract.LiveChangePlan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime

/** Historical execution evidence only. Neither variant admits a live operation. */
sealed interface HistoricalLiveExecution {
    /** Exact operation admitted by the existing private endpoint and original native read owner. */
    class LocalEndpointOperation
    private constructor(
        val planId: ChangePlanId,
        val root: CanonicalWorkspaceRoot,
        val host: IdeReadHostLifetime,
        val operation: LiveChangeEffect,
    ) : HistoricalLiveExecution {
        companion object {
            internal fun fromAuthority(authority: LiveMutationAuthority): LocalEndpointOperation {
                val plan = authority.plan
                val before = plan.basis.observation.reference
                return LocalEndpointOperation(
                    plan.planId,
                    before.workspaceRoot,
                    before.host,
                    LiveChangeEffect.CHANGE_APPLY,
                )
            }

            internal fun restore(
                plan: LiveChangePlan,
                planId: ChangePlanId,
                root: CanonicalWorkspaceRoot,
                host: IdeReadHostLifetime,
                operation: LiveChangeEffect,
            ): Refinement<LocalEndpointOperation, LiveReceiptFailure> {
                val execution = LocalEndpointOperation(planId, root, host, operation)
                return if (execution.matches(plan)) Refinement.Refined(execution)
                else Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
            }
        }
    }
}

internal fun HistoricalLiveExecution.matches(plan: LiveChangePlan): Boolean =
    when (this) {
        is HistoricalLiveApproval -> true // Legacy evidence never restores a live approval capability.
        is HistoricalLiveExecution.LocalEndpointOperation ->
            operation == LiveChangeEffect.CHANGE_APPLY &&
                planId == plan.planId &&
                root == plan.basis.observation.reference.workspaceRoot &&
                host == plan.basis.observation.reference.host
    }

/** The old receipt's bounded nonce remains historical data, never a current challenge or credential. */
@JvmInline
value class HistoricalApprovalChallenge private constructor(val value: String) {
    internal companion object {
        fun parse(value: String): Refinement<HistoricalApprovalChallenge, LiveReceiptFailure> =
            if (value.matches(Regex("[0-9a-f]{64}"))) Refinement.Refined(HistoricalApprovalChallenge(value))
            else Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
    }
}
