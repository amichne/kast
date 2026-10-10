package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason

internal sealed interface WorkspacePreparationAction {
    data object Ready : WorkspacePreparationAction

    data object Wait : WorkspacePreparationAction

    data object ReloadModel : WorkspacePreparationAction

    data class Blocked(val reason: IdeLifecycleFailure) : WorkspacePreparationAction
}

/** Decisions consume detached native facts; opening revisions cannot override a current Ready observation. */
internal class WorkspaceReadinessPreparation {
    private var requestedReload = false

    fun observe(readiness: WorkspaceCapabilityReadiness): WorkspacePreparationAction =
        when (readiness) {
            is WorkspaceCapabilityReadiness.Ready -> WorkspacePreparationAction.Ready
            is WorkspaceCapabilityReadiness.Pending -> action(readiness.reason, readiness.nextAction)
            is WorkspaceCapabilityReadiness.Unavailable -> action(readiness.reason, readiness.nextAction)
            is WorkspaceCapabilityReadiness.Blocked -> action(readiness.reason, readiness.nextAction)
        }

    private fun action(
        reason: WorkspaceReadinessReason,
        next: WorkspaceReadinessNextAction,
    ): WorkspacePreparationAction =
        when (next) {
            WorkspaceReadinessNextAction.REFRESH_MODEL ->
                if (requestedReload) {
                    WorkspacePreparationAction.Blocked(IdeLifecycleFailure.IMPORT_FAILED)
                } else {
                    requestedReload = true
                    WorkspacePreparationAction.ReloadModel
                }
            WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT -> WorkspacePreparationAction.Wait
            WorkspaceReadinessNextAction.REOPEN_PROJECT ->
                WorkspacePreparationAction.Blocked(IdeLifecycleFailure.DISPOSED)
            WorkspaceReadinessNextAction.ATTACH_HOST ->
                WorkspacePreparationAction.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)
            WorkspaceReadinessNextAction.SELECT_PROJECT ->
                WorkspacePreparationAction.Blocked(IdeLifecycleFailure.STALE_PROJECT)
            WorkspaceReadinessNextAction.CHECK_CONFIGURATION ->
                WorkspacePreparationAction.Blocked(
                    if (reason == WorkspaceReadinessReason.HOST_UNAVAILABLE) IdeLifecycleFailure.HOST_UNAVAILABLE
                    else IdeLifecycleFailure.PLATFORM_UNAVAILABLE
                )
        }
}
