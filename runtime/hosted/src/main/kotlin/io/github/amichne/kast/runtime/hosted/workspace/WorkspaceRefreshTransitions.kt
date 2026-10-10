package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness

internal sealed interface WorkspaceRefreshEffectKind {
    data class Explicit(val effect: WorkspaceRefreshEffect) : WorkspaceRefreshEffectKind

    data object Incremental : WorkspaceRefreshEffectKind
}

internal data class WorkspaceRefreshWork(val effect: WorkspaceRefreshEffectKind, val stamp: WorkspaceRefreshStamp)

// Referential identity prevents a duplicate native callback from settling an identical later retry.
internal class WorkspaceRefreshAttempt(
    val work: WorkspaceRefreshWork,
    val id: WorkspaceRefreshAttemptId,
    val initiator: WorkspaceRefreshWaiterIdentity,
    val observation: WorkspaceCapabilityReadiness.Ready?,
)

internal sealed interface WorkspaceRefreshWaiter {
    data class Explicit(val id: WorkspaceRefreshRequestId) : WorkspaceRefreshWaiter

    class Read : WorkspaceRefreshWaiter
}

internal class WorkspaceRefreshEntry(
    val attempt: WorkspaceRefreshAttempt,
    val started: Long,
    var status: WorkspaceRefreshStatus,
    val complete: (WorkspaceRefreshStatus) -> Unit = {},
)

internal fun WorkspaceRefreshWaiter.identity(): WorkspaceRefreshWaiterIdentity =
    when (this) {
        is WorkspaceRefreshWaiter.Explicit -> WorkspaceRefreshWaiterIdentity.Request(id)
        is WorkspaceRefreshWaiter.Read -> WorkspaceRefreshWaiterIdentity.ReadPreparation
    }

internal fun WorkspaceRefreshAttempt.inspection(
    entries: Map<WorkspaceRefreshWaiter, WorkspaceRefreshEntry>
): WorkspaceRefreshAttemptInspection =
    WorkspaceRefreshAttemptInspection(
        id,
        when (val effect = work.effect) {
            WorkspaceRefreshEffectKind.Incremental -> WorkspaceRefreshNativeEffect.INCREMENTAL_FILES
            is WorkspaceRefreshEffectKind.Explicit ->
                when (effect.effect) {
                    WorkspaceRefreshEffect.FILE_REFRESH -> WorkspaceRefreshNativeEffect.FORCED_FILES
                    WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD -> WorkspaceRefreshNativeEffect.MODEL_RELOAD
                }
        },
        work.stamp,
        initiator,
        entries
            .filterValues { it.attempt === this }
            .map { (waiter, entry) ->
                WorkspaceRefreshWaiterInspection(waiter.identity(), entry.status)
            },
    )

internal fun WorkspaceRefreshEffectResult.settledStatus(effect: WorkspaceRefreshEffectKind): WorkspaceRefreshStatus =
    when (this) {
        WorkspaceRefreshEffectResult.BUSY -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.BUSY)
        WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS ->
            WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.UNSAVED_DOCUMENTS)
        WorkspaceRefreshEffectResult.UNLINKED_BUILD ->
            WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.UNLINKED_BUILD)
        WorkspaceRefreshEffectResult.ROOT_UNAVAILABLE ->
            WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.ROOT_UNAVAILABLE)
        WorkspaceRefreshEffectResult.SUCCEEDED ->
            if (effect == WorkspaceRefreshEffectKind.Incremental) WorkspaceRefreshStatus.Complete
            else WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION)
        WorkspaceRefreshEffectResult.FAILED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.EFFECT_FAILED)
        WorkspaceRefreshEffectResult.CANCELLED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED)
        WorkspaceRefreshEffectResult.DISPOSED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED)
        WorkspaceRefreshEffectResult.RETIRED -> error("Retirement is handled before terminal settlement")
    }

/** Coalescing requires current native epoch equality; missing observations never stand in for that proof. */
internal fun List<WorkspaceRefreshAttempt>.equivalentModelDemand(
    effect: WorkspaceRefreshEffect,
    observed: WorkspaceCapabilityReadiness.Ready?,
): WorkspaceRefreshAttempt? {
    if (observed == null || effect != WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD) return null
    return firstOrNull { attempt ->
        val previous = attempt.observation
        if (attempt.work.effect != WorkspaceRefreshEffectKind.Explicit(effect) || previous == null)
            return@firstOrNull false
        previous.identity == observed.identity &&
            previous.epoch.relationTo(observed.epoch) == ProjectReadEpochRelation.SAME
    }
}
