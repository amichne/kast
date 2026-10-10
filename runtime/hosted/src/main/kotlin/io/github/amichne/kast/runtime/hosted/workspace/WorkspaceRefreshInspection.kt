package io.github.amichne.kast.runtime.hosted.workspace

@JvmInline
internal value class WorkspaceRefreshAttemptId private constructor(val value: Long) {
    companion object {
        fun fromSequence(sequence: Long): WorkspaceRefreshAttemptId {
            require(sequence > 0)
            return WorkspaceRefreshAttemptId(sequence)
        }
    }
}

internal enum class WorkspaceRefreshNativeEffect {
    INCREMENTAL_FILES,
    FORCED_FILES,
    MODEL_RELOAD,
}

internal sealed interface WorkspaceRefreshWaiterIdentity {
    data class Request(val id: WorkspaceRefreshRequestId) : WorkspaceRefreshWaiterIdentity

    data object ReadPreparation : WorkspaceRefreshWaiterIdentity
}

internal data class WorkspaceRefreshWaiterInspection(
    val identity: WorkspaceRefreshWaiterIdentity,
    val status: WorkspaceRefreshStatus,
)

internal data class WorkspaceRefreshAttemptInspection(
    val id: WorkspaceRefreshAttemptId,
    val effect: WorkspaceRefreshNativeEffect,
    val stamp: WorkspaceRefreshStamp,
    val initiator: WorkspaceRefreshWaiterIdentity,
    val waiters: List<WorkspaceRefreshWaiterInspection>,
)

internal sealed interface WorkspaceRefreshInspection {
    data object Idle : WorkspaceRefreshInspection

    data class Retired(val unsettled: List<WorkspaceRefreshAttemptInspection>) : WorkspaceRefreshInspection

    data class Running(
        val active: WorkspaceRefreshAttemptInspection,
        val queued: List<WorkspaceRefreshAttemptInspection>,
    ) : WorkspaceRefreshInspection

    data class AwaitingAdmission(val waiters: List<WorkspaceRefreshWaiterInspection>) : WorkspaceRefreshInspection
}

/** Detached inspection projects owner transitions without observing native readiness or authorizing effects. */
internal fun workspaceRefreshInspection(
    disposed: Boolean,
    active: WorkspaceRefreshAttempt?,
    queue: Iterable<WorkspaceRefreshAttempt>,
    entries: Map<WorkspaceRefreshWaiter, WorkspaceRefreshEntry>,
): WorkspaceRefreshInspection {
    if (disposed) return WorkspaceRefreshInspection.Retired(listOfNotNull(active?.inspection(entries)))
    active?.let {
        return WorkspaceRefreshInspection.Running(
            it.inspection(entries),
            queue.map { queued -> queued.inspection(entries) },
        )
    }
    val admission = entries.filterValues {
        it.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION)
    }
    if (admission.isNotEmpty())
        return WorkspaceRefreshInspection.AwaitingAdmission(
            admission.map { (waiter, entry) -> WorkspaceRefreshWaiterInspection(waiter.identity(), entry.status) }
        )
    return WorkspaceRefreshInspection.Idle
}
