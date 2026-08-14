package io.github.amichne.kast.workspace.contract

enum class WorkspaceExpensiveWork {
    RUNTIME_START,
    PROJECT_IMPORT,
    WORKSPACE_TRANSITION,
    INDEXING,
    LONG_OPERATION,
}

sealed interface WorkspaceEdtLiveness {
    data object Live : WorkspaceEdtLiveness

    data object Frozen : WorkspaceEdtLiveness

    data object Unavailable : WorkspaceEdtLiveness
}

/**
 * Detached counts for physical work already active outside the coordinating controller.
 *
 * Observation adapters must not count the controller's own initiation entries a second time.
 */
data class WorkspaceResourceActivity(
    val runtimeStarts: WorkspaceResourceCount,
    val imports: WorkspaceResourceCount,
    val transitions: WorkspaceResourceCount,
    val indexing: WorkspaceResourceCount,
    val longOperations: WorkspaceResourceCount,
) {
    fun active(kind: WorkspaceExpensiveWork): WorkspaceResourceCount = when (kind) {
        WorkspaceExpensiveWork.RUNTIME_START -> runtimeStarts
        WorkspaceExpensiveWork.PROJECT_IMPORT -> imports
        WorkspaceExpensiveWork.WORKSPACE_TRANSITION -> transitions
        WorkspaceExpensiveWork.INDEXING -> indexing
        WorkspaceExpensiveWork.LONG_OPERATION -> longOperations
    }

    /**
     * Proof transition:
     * `WorkspaceResourceActivity + WorkspaceExpensiveWork + WorkspaceResourceCount`
     * `-> WorkspaceResourceActivity`.
     *
     * Replaces exactly one kind's already-refined active count without changing other observations.
     */
    fun withActive(
        kind: WorkspaceExpensiveWork,
        count: WorkspaceResourceCount,
    ): WorkspaceResourceActivity = when (kind) {
        WorkspaceExpensiveWork.RUNTIME_START -> copy(runtimeStarts = count)
        WorkspaceExpensiveWork.PROJECT_IMPORT -> copy(imports = count)
        WorkspaceExpensiveWork.WORKSPACE_TRANSITION -> copy(transitions = count)
        WorkspaceExpensiveWork.INDEXING -> copy(indexing = count)
        WorkspaceExpensiveWork.LONG_OPERATION -> copy(longOperations = count)
    }

    companion object {
        fun none(): WorkspaceResourceActivity = WorkspaceResourceActivity(
            runtimeStarts = WorkspaceResourceCount.none(),
            imports = WorkspaceResourceCount.none(),
            transitions = WorkspaceResourceCount.none(),
            indexing = WorkspaceResourceCount.none(),
            longOperations = WorkspaceResourceCount.none(),
        )
    }
}

/** One detached point-in-time resource observation; no semantic readiness is carried here. */
data class WorkspaceResourceObservation(
    val heap: WorkspaceHeapUtilizationPercent,
    val edt: WorkspaceEdtLiveness,
    val activity: WorkspaceResourceActivity,
)

sealed interface WorkspaceResourceBlocker {
    data class HeapCritical(
        val observed: WorkspaceHeapUtilizationPercent,
        val threshold: WorkspaceCriticalHeapPercent,
    ) : WorkspaceResourceBlocker

    data class EdtUnavailable(
        val observation: WorkspaceEdtLiveness,
    ) : WorkspaceResourceBlocker

    data class Capacity(
        val kind: WorkspaceExpensiveWork,
        val limit: WorkspaceConcurrencyLimit,
    ) : WorkspaceResourceBlocker

    data class QueueFull(
        val limit: WorkspaceQueueLimit,
    ) : WorkspaceResourceBlocker

    data class WaitTimedOut(
        val timeout: WorkspaceAdmissionWaitMillis,
    ) : WorkspaceResourceBlocker

    data object WaitInterrupted : WorkspaceResourceBlocker

    data class InitiationFailed(
        val kind: WorkspaceExpensiveWork,
    ) : WorkspaceResourceBlocker
}

enum class WorkspaceResourceAdmissionAction {
    START,
    REUSE_EXACT_ROOT,
    RETRY_AFTER_RELEASE,
    RECOVER_HEAP,
    RECOVER_EDT,
}

@JvmInline
value class WorkspaceResourceDurationNanos private constructor(
    val nanoseconds: Long,
) {
    fun plus(other: WorkspaceResourceDurationNanos): WorkspaceResourceDurationNanos =
        WorkspaceResourceDurationNanos(Math.addExact(nanoseconds, other.nanoseconds))

    fun excluding(other: WorkspaceResourceDurationNanos): WorkspaceResourceDurationNanos =
        WorkspaceResourceDurationNanos((nanoseconds - other.nanoseconds).coerceAtLeast(0L))

    companion object {
        fun zero(): WorkspaceResourceDurationNanos = WorkspaceResourceDurationNanos(0L)

        /** Captures non-negative elapsed time at the monotonic-clock boundary. */
        fun elapsed(
            startedAt: Long,
            finishedAt: Long,
        ): WorkspaceResourceDurationNanos =
            WorkspaceResourceDurationNanos((finishedAt - startedAt).coerceAtLeast(0L))
    }
}

data class WorkspaceResourceAdmissionTiming(
    val queue: WorkspaceResourceDurationNanos,
    val admission: WorkspaceResourceDurationNanos,
)

sealed interface WorkspaceResourceInitiationResult {
    val timing: WorkspaceResourceAdmissionTiming
    val action: WorkspaceResourceAdmissionAction

    data class Initiated(
        override val timing: WorkspaceResourceAdmissionTiming,
    ) : WorkspaceResourceInitiationResult {
        override val action: WorkspaceResourceAdmissionAction = WorkspaceResourceAdmissionAction.START
    }

    data class ReusedExactRoot(
        override val timing: WorkspaceResourceAdmissionTiming,
    ) : WorkspaceResourceInitiationResult {
        override val action: WorkspaceResourceAdmissionAction =
            WorkspaceResourceAdmissionAction.REUSE_EXACT_ROOT
    }

    data class Rejected(
        val blocker: WorkspaceResourceBlocker,
        override val action: WorkspaceResourceAdmissionAction,
        override val timing: WorkspaceResourceAdmissionTiming,
    ) : WorkspaceResourceInitiationResult
}
