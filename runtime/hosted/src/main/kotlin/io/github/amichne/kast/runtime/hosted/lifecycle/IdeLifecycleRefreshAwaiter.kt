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
): IdeLifecycleResult {
    var result = initial
    val finished =
        awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
            val pending = result as? WorkspaceRefreshResult.Pending
            if (pending != null) {
                result = status(pending.requestId)
                val current = result as? WorkspaceRefreshResult.Pending
                if (current != null) {
                    val stage =
                        if (current.stage == WorkspaceRefreshStage.ADMISSION) IdeLifecycleStage.ADMISSION
                        else IdeLifecycleStage.IMPORTING
                    progress(operation.enter(stage))
                }
            }
            result !is WorkspaceRefreshResult.Pending
        }
    if (!finished) return IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
    return when (val terminal = result) {
        is WorkspaceRefreshResult.Complete -> {
            operation.enter(IdeLifecycleStage.ADMISSION)
            IdeLifecycleResult.Synced(target)
        }
        is WorkspaceRefreshResult.Failed ->
            IdeLifecycleResult.Blocked(terminal.reason.lifecycleFailure(), terminal.preparation)
        is WorkspaceRefreshResult.Rejected -> IdeLifecycleResult.Blocked(terminal.reason.lifecycleFailure())
        else -> IdeLifecycleResult.Blocked(IdeLifecycleFailure.IMPORT_FAILED)
    }
}
