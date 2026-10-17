package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import io.github.amichne.kast.protocol.contract.WorkspacePreparationTiming

/** Expiring a waiter preserves the native attempt and its callback authority. */
internal fun WorkspaceRefreshEntry.expire(
    waiter: WorkspaceRefreshWaiter,
    now: Long,
    observe: (WorkspaceRefreshDeadlineObservation) -> Unit,
) {
    val pending = status as? WorkspaceRefreshStatus.Pending ?: return
    if (now - started < allowanceNanos) return
    val stage =
        when (pending.stage) {
            WorkspaceRefreshStage.QUEUED -> IdeLifecycleStage.OPENING
            WorkspaceRefreshStage.EFFECT -> IdeLifecycleStage.IMPORTING
            WorkspaceRefreshStage.ADMISSION -> IdeLifecycleStage.ADMISSION
        }
    val timing =
        WorkspacePreparationTiming(
            stage,
            (now - started).coerceAtLeast(0) / NANOS_PER_MILLI,
            allowanceNanos / NANOS_PER_MILLI,
            0,
            WorkspacePreparationDeadlineOwner.REFRESH_WAITER,
        )
    deadlineTiming = timing
    observe(WorkspaceRefreshDeadlineObservation((waiter as? WorkspaceRefreshWaiter.Explicit)?.id?.value, timing))
    transition(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED))
}

private const val NANOS_PER_MILLI = 1_000_000L
