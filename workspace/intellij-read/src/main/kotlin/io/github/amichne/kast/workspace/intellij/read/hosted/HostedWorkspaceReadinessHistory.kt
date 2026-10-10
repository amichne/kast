package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail

/**
 * Query-owner history for inspection only. A rejected current observation never becomes cached Ready. No caller
 * metadata or refresh completion participates, and an observation from another identity is never attached.
 */
internal class HostedWorkspaceReadinessHistory {
    private var previous: WorkspaceCapabilityReadiness.Ready? = null

    @Synchronized
    fun record(current: WorkspaceCapabilityReadiness): WorkspaceCapabilityReadiness {
        if (current is WorkspaceCapabilityReadiness.Ready) {
            previous = current
            return current
        }
        val retained = previous?.takeIf { it.identity == current.identity } ?: return current
        fun detail(original: WorkspaceReadinessDetail): WorkspaceReadinessDetail =
            WorkspaceReadinessDetail.PreviouslyObservedModel(
                retained,
                when (original) {
                    is WorkspaceReadinessDetail.PreviouslyObservedModel -> original.currentDetail
                    else -> original
                },
            )
        return when (current) {
            is WorkspaceCapabilityReadiness.Ready -> current
            is WorkspaceCapabilityReadiness.Unavailable -> current.copy(detail = detail(current.detail))
            is WorkspaceCapabilityReadiness.Pending -> current.copy(detail = detail(current.detail))
            is WorkspaceCapabilityReadiness.Blocked -> current.copy(detail = detail(current.detail))
        }
    }
}
