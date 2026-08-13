package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement

enum class WorkspacePositiveLimitFailure {
    NOT_POSITIVE,
}

enum class WorkspaceAdmissionWaitFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

enum class WorkspacePercentageFailure {
    OUT_OF_RANGE,
}

enum class WorkspaceResourceCountFailure {
    NEGATIVE,
}

@JvmInline
value class WorkspaceConcurrencyLimit private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `Int -> Refinement<WorkspaceConcurrencyLimit, WorkspacePositiveLimitFailure>`.
         *
         * Establishes a strictly positive concurrency ceiling. [WorkspacePositiveLimitFailure] is
         * the closed expected failure. Raw extraction is permitted only inside resource admission.
         */
        fun parse(
            raw: Int,
        ): Refinement<WorkspaceConcurrencyLimit, WorkspacePositiveLimitFailure> =
            positive(raw, ::WorkspaceConcurrencyLimit)
    }
}

@JvmInline
value class WorkspaceQueueLimit private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `Int -> Refinement<WorkspaceQueueLimit, WorkspacePositiveLimitFailure>`.
         *
         * Establishes a strictly positive global waiter ceiling. [WorkspacePositiveLimitFailure]
         * is the closed expected failure. Raw extraction is permitted only inside queue admission.
         */
        fun parse(
            raw: Int,
        ): Refinement<WorkspaceQueueLimit, WorkspacePositiveLimitFailure> =
            positive(raw, ::WorkspaceQueueLimit)
    }
}

@JvmInline
value class WorkspaceAdmissionWaitMillis private constructor(
    val value: Long,
) {
    val nanoseconds: Long
        get() = value * NANOS_PER_MILLISECOND

    companion object {
        /**
         * Proof transition:
         * `Long -> Refinement<WorkspaceAdmissionWaitMillis, WorkspaceAdmissionWaitFailure>`.
         *
         * Establishes a positive absolute queue budget whose nanosecond representation cannot
         * overflow. [WorkspaceAdmissionWaitFailure] is the closed expected failure. Raw extraction
         * is permitted only at the bounded wait boundary.
         */
        fun parse(
            raw: Long,
        ): Refinement<WorkspaceAdmissionWaitMillis, WorkspaceAdmissionWaitFailure> = when {
            raw <= 0L -> Refinement.Rejected(WorkspaceAdmissionWaitFailure.NOT_POSITIVE)
            raw > Long.MAX_VALUE / NANOS_PER_MILLISECOND ->
                Refinement.Rejected(WorkspaceAdmissionWaitFailure.TOO_LARGE)
            else -> Refinement.Refined(WorkspaceAdmissionWaitMillis(raw))
        }
    }
}

@JvmInline
value class WorkspaceCriticalHeapPercent private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `Int -> Refinement<WorkspaceCriticalHeapPercent, WorkspacePercentageFailure>`.
         *
         * Establishes a critical heap threshold in the inclusive range 1..100.
         * [WorkspacePercentageFailure] is the closed expected failure. Raw extraction is permitted
         * only inside heap admission.
         */
        fun parse(
            raw: Int,
        ): Refinement<WorkspaceCriticalHeapPercent, WorkspacePercentageFailure> =
            if (raw in 1..100) {
                Refinement.Refined(WorkspaceCriticalHeapPercent(raw))
            } else {
                Refinement.Rejected(WorkspacePercentageFailure.OUT_OF_RANGE)
            }
    }
}

@JvmInline
value class WorkspaceHeapUtilizationPercent private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `Int -> Refinement<WorkspaceHeapUtilizationPercent, WorkspacePercentageFailure>`.
         *
         * Establishes an observed heap utilization in the inclusive range 0..100.
         * [WorkspacePercentageFailure] is the closed expected failure. Raw extraction is permitted
         * only by physical heap observation and resource admission.
         */
        fun parse(
            raw: Int,
        ): Refinement<WorkspaceHeapUtilizationPercent, WorkspacePercentageFailure> =
            if (raw in 0..100) {
                Refinement.Refined(WorkspaceHeapUtilizationPercent(raw))
            } else {
                Refinement.Rejected(WorkspacePercentageFailure.OUT_OF_RANGE)
            }
    }
}

@JvmInline
value class WorkspaceResourceCount private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `Int -> Refinement<WorkspaceResourceCount, WorkspaceResourceCountFailure>`.
         *
         * Establishes a non-negative detached active-resource count. [WorkspaceResourceCountFailure]
         * is the closed expected failure. Raw extraction is permitted only by observation adapters
         * and resource admission.
         */
        fun parse(
            raw: Int,
        ): Refinement<WorkspaceResourceCount, WorkspaceResourceCountFailure> =
            if (raw >= 0) {
                Refinement.Refined(WorkspaceResourceCount(raw))
            } else {
                Refinement.Rejected(WorkspaceResourceCountFailure.NEGATIVE)
            }

        fun none(): WorkspaceResourceCount = WorkspaceResourceCount(0)
    }
}

data class WorkspaceResourcePolicy(
    val runtimeStarts: WorkspaceConcurrencyLimit,
    val imports: WorkspaceConcurrencyLimit,
    val transitions: WorkspaceConcurrencyLimit,
    val indexing: WorkspaceConcurrencyLimit,
    val longOperations: WorkspaceConcurrencyLimit,
    val queuedWaiters: WorkspaceQueueLimit,
    val waitTimeout: WorkspaceAdmissionWaitMillis,
    val criticalHeap: WorkspaceCriticalHeapPercent,
) {
    fun limitFor(kind: WorkspaceExpensiveWork): WorkspaceConcurrencyLimit = when (kind) {
        WorkspaceExpensiveWork.RUNTIME_START -> runtimeStarts
        WorkspaceExpensiveWork.PROJECT_IMPORT -> imports
        WorkspaceExpensiveWork.WORKSPACE_TRANSITION -> transitions
        WorkspaceExpensiveWork.INDEXING -> indexing
        WorkspaceExpensiveWork.LONG_OPERATION -> longOperations
    }
}

private fun <Strong> positive(
    raw: Int,
    create: (Int) -> Strong,
): Refinement<Strong, WorkspacePositiveLimitFailure> =
    if (raw > 0) {
        Refinement.Refined(create(raw))
    } else {
        Refinement.Rejected(WorkspacePositiveLimitFailure.NOT_POSITIVE)
    }

private const val NANOS_PER_MILLISECOND = 1_000_000L
