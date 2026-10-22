package io.github.amichne.kast.kernel

enum class PositiveLimitFailure {
    NOT_POSITIVE
}

@JvmInline
value class ResultLimit private constructor(val value: Int) {
    fun intersect(other: ResultLimit): ResultLimit = if (value <= other.value) this else other

    companion object {
        /**
         * Proof transition: `Int -> Refinement<ResultLimit, PositiveLimitFailure>`.
         *
         * Establishes a finite, strictly positive result cardinality bound. [PositiveLimitFailure] is the closed
         * expected failure. Raw integers may be extracted only at an operation-definition or request-budget boundary.
         */
        fun parse(raw: Int): Refinement<ResultLimit, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(ResultLimit(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

@JvmInline
value class WorkUnitLimit private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<WorkUnitLimit, PositiveLimitFailure>`.
         *
         * Establishes a finite, strictly positive abstract-work bound. [PositiveLimitFailure] is the closed expected
         * failure. Raw longs may be extracted only at an operation-definition or request-budget boundary.
         */
        fun parse(raw: Long): Refinement<WorkUnitLimit, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(WorkUnitLimit(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

@JvmInline
value class ElapsedTimeLimitMillis private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<ElapsedTimeLimitMillis, PositiveLimitFailure>`.
         *
         * Establishes a finite, strictly positive elapsed-time bound in milliseconds. [PositiveLimitFailure] is the
         * closed expected failure. Raw longs may be extracted only at an operation-definition or request-budget
         * boundary.
         */
        fun parse(raw: Long): Refinement<ElapsedTimeLimitMillis, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(ElapsedTimeLimitMillis(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

sealed interface ResourceBudget {
    val resultAllowance: ResultAllowance
    val workAllowance: WorkAllowance

    data class Bounded(
        val resultLimit: ResultLimit,
        val workUnitLimit: WorkUnitLimit,
        val elapsedTimeLimit: ElapsedTimeLimitMillis,
    ) : ResourceBudget {
        override val resultAllowance = ResultAllowance.Bounded(resultLimit)
        override val workAllowance = WorkAllowance.Bounded(workUnitLimit)
    }

    data object RunnerControlled : ResourceBudget {
        override val resultAllowance = ResultAllowance.Unlimited
        override val workAllowance = WorkAllowance.Unlimited
    }

    fun permitsResults(count: Int): Boolean =
        when (this) {
            is Bounded -> count <= resultLimit.value
            RunnerControlled -> true
        }

    fun permitsWork(count: Long): Boolean =
        when (this) {
            is Bounded -> count <= workUnitLimit.value
            RunnerControlled -> true
        }

    fun hasResultCapacity(count: Int): Boolean =
        when (this) {
            is Bounded -> count < resultLimit.value
            RunnerControlled -> true
        }

    fun hasWorkCapacity(count: Long): Boolean =
        when (this) {
            is Bounded -> count < workUnitLimit.value
            RunnerControlled -> true
        }

    fun permitsElapsedMillis(millis: Long): Boolean =
        when (this) {
            is Bounded -> millis <= elapsedTimeLimit.value
            RunnerControlled -> true
        }

    fun hasTimeRemaining(millis: Long): Boolean =
        when (this) {
            is Bounded -> millis < elapsedTimeLimit.value
            RunnerControlled -> true
        }

    fun resultsFitWithin(parent: ResourceBudget): Boolean =
        when (this) {
            is Bounded -> parent.permitsResults(resultLimit.value)
            RunnerControlled -> parent == RunnerControlled
        }

    fun workFitsWithin(parent: ResourceBudget): Boolean =
        when (this) {
            is Bounded -> parent.permitsWork(workUnitLimit.value)
            RunnerControlled -> parent == RunnerControlled
        }

    fun timeFitsWithin(parent: ResourceBudget): Boolean =
        when (this) {
            is Bounded -> parent.permitsElapsedMillis(elapsedTimeLimit.value)
            RunnerControlled -> parent == RunnerControlled
        }

    fun remainingAfter(work: Long, elapsedMillis: Long): Refinement<ResourceBudget, PositiveLimitFailure> =
        when (this) {
            RunnerControlled -> Refinement.Refined(this)
            is Bounded ->
                when (val remainingWork = WorkUnitLimit.parse(workUnitLimit.value - work)) {
                    is Refinement.Rejected -> remainingWork
                    is Refinement.Refined ->
                        when (
                            val remainingTime = ElapsedTimeLimitMillis.parse(elapsedTimeLimit.value - elapsedMillis)
                        ) {
                            is Refinement.Rejected -> remainingTime
                            is Refinement.Refined ->
                                Refinement.Refined(
                                    copy(workUnitLimit = remainingWork.value, elapsedTimeLimit = remainingTime.value)
                                )
                        }
                }
        }

    fun intersect(other: ResourceBudget): ResourceBudget =
        when (this) {
            RunnerControlled -> other
            is Bounded ->
                when (other) {
                    RunnerControlled -> this
                    is Bounded ->
                        Bounded(
                            resultLimit.intersect(other.resultLimit),
                            if (workUnitLimit.value <= other.workUnitLimit.value) workUnitLimit
                            else other.workUnitLimit,
                            if (elapsedTimeLimit.value <= other.elapsedTimeLimit.value) elapsedTimeLimit
                            else other.elapsedTimeLimit,
                        )
                }
        }

    fun withResultLimit(limit: ResultLimit): ResourceBudget =
        when (this) {
            RunnerControlled -> this
            is Bounded -> copy(resultLimit = resultLimit.intersect(limit))
        }

    fun fitsWithin(parent: ResourceBudget): Boolean =
        resultsFitWithin(parent) && workFitsWithin(parent) && timeFitsWithin(parent)

    companion object {
        operator fun invoke(
            resultLimit: ResultLimit,
            workUnitLimit: WorkUnitLimit,
            elapsedTimeLimit: ElapsedTimeLimitMillis,
        ): Bounded = Bounded(resultLimit, workUnitLimit, elapsedTimeLimit)
    }
}
