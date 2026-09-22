package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement

internal enum class WorkspaceExecutionPolicyFailure {
    QUEUED_LIMIT_REJECTED,
    WAIT_LIMIT_REJECTED,
    INTERACTION_LIMIT_REJECTED,
}

/** Queue allowance is part of one aggregate interaction allowance, never a renewed execution budget. */
internal class WorkspaceExecutionPolicy
private constructor(
    val maximumQueued: Int,
    val queueWait: ElapsedTimeLimitMillis,
    val interaction: ElapsedTimeLimitMillis,
) {
    companion object {
        val Default =
            WorkspaceExecutionPolicy(
                BrokerOperationalLimits.defaultWorkspaceQueued,
                BrokerOperationalLimits.workspaceQueueWait,
                BrokerOperationalLimits.workspaceInteraction,
            )

        fun admit(
            maximumQueued: Int,
            queueWaitMillis: Long,
            interactionMillis: Long,
        ): Refinement<WorkspaceExecutionPolicy, WorkspaceExecutionPolicyFailure> {
            if (maximumQueued !in 0..BrokerOperationalLimits.maximumWorkspaceQueued)
                return Refinement.Rejected(WorkspaceExecutionPolicyFailure.QUEUED_LIMIT_REJECTED)
            val queueWait =
                when (val admitted = ElapsedTimeLimitMillis.parse(queueWaitMillis)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(WorkspaceExecutionPolicyFailure.WAIT_LIMIT_REJECTED)
                }
            val interaction =
                when (val admitted = ElapsedTimeLimitMillis.parse(interactionMillis)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(WorkspaceExecutionPolicyFailure.INTERACTION_LIMIT_REJECTED)
                }
            if (queueWait.value > interaction.value)
                return Refinement.Rejected(WorkspaceExecutionPolicyFailure.WAIT_LIMIT_REJECTED)
            return Refinement.Refined(WorkspaceExecutionPolicy(maximumQueued, queueWait, interaction))
        }
    }
}
