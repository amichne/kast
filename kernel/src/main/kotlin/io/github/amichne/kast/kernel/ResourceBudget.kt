package io.github.amichne.kast.kernel

enum class PositiveLimitFailure {
    NOT_POSITIVE,
}

@JvmInline
value class ResultLimit private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ResultLimit, PositiveLimitFailure>`.
         *
         * Establishes a finite, strictly positive result cardinality bound.
         * [PositiveLimitFailure] is the closed expected failure. Raw integers may be extracted
         * only at an operation-definition or request-budget boundary.
         */
        fun parse(raw: Int): Refinement<ResultLimit, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(ResultLimit(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

@JvmInline
value class WorkUnitLimit private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<WorkUnitLimit, PositiveLimitFailure>`.
         *
         * Establishes a finite, strictly positive abstract-work bound.
         * [PositiveLimitFailure] is the closed expected failure. Raw longs may be extracted only
         * at an operation-definition or request-budget boundary.
         */
        fun parse(raw: Long): Refinement<WorkUnitLimit, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(WorkUnitLimit(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

@JvmInline
value class ElapsedTimeLimitMillis private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<ElapsedTimeLimitMillis, PositiveLimitFailure>`.
         *
         * Establishes a finite, strictly positive elapsed-time bound in milliseconds.
         * [PositiveLimitFailure] is the closed expected failure. Raw longs may be extracted only
         * at an operation-definition or request-budget boundary.
         */
        fun parse(raw: Long): Refinement<ElapsedTimeLimitMillis, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(ElapsedTimeLimitMillis(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

data class ResourceBudget(
    val resultLimit: ResultLimit,
    val workUnitLimit: WorkUnitLimit,
    val elapsedTimeLimit: ElapsedTimeLimitMillis,
)
