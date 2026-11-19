package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage

/** Poll the issued refresh identity; child refreshes retain their parent's deadline and monotonic lifecycle stage. */
internal suspend fun awaitLifecycleRefresh(
    target: IdeProjectTarget,
    operation: NativeWorkspacePreparation,
    initial: WorkspaceRefreshResult,
    status: (String) -> WorkspaceRefreshResult,
    progress: (IdeLifecycleStage) -> Unit,
    effectSettled: () -> Unit = {},
): IdeLifecycleResult {
    var result = initial
    if (initial.admissionPending()) effectSettled()
    val finished =
        awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
            val pending = result as? WorkspaceRefreshResult.Pending ?: return@awaitLifecycleCondition true
            result = status(pending.requestId)
            if (pending.stage != WorkspaceRefreshStage.ADMISSION && result.admissionPending()) effectSettled()
            if (result is WorkspaceRefreshResult.Pending) {
                val stage = if (result.admissionPending()) IdeLifecycleStage.ADMISSION else IdeLifecycleStage.IMPORTING
                progress(operation.enter(stage))
            }
            result !is WorkspaceRefreshResult.Pending
        }
    if (!finished) return IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
    return refreshResult(result, operation, target)
}

private fun WorkspaceRefreshResult.admissionPending() =
    this is WorkspaceRefreshResult.Pending && stage == WorkspaceRefreshStage.ADMISSION

private fun refreshResult(
    terminal: WorkspaceRefreshResult,
    operation: NativeWorkspacePreparation,
    target: IdeProjectTarget,
): IdeLifecycleResult =
    when (terminal) {
        is WorkspaceRefreshResult.Complete -> {
            operation.enter(IdeLifecycleStage.ADMISSION)
            IdeLifecycleResult.Synced(target)
        }
        is WorkspaceRefreshResult.Failed ->
            IdeLifecycleResult.Blocked(terminal.reason.lifecycleFailure(), terminal.preparation)
        is WorkspaceRefreshResult.Rejected -> IdeLifecycleResult.Blocked(terminal.reason.lifecycleFailure())
        else -> IdeLifecycleResult.Blocked(IdeLifecycleFailure.IMPORT_FAILED)
    }
