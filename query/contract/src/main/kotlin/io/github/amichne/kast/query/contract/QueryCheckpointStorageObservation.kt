package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

/** Immutable detached task ownership; storage arithmetic grants no semantic authority. */
interface QueryCheckpointStorageOwner {
    fun retainedBytes(graph: QueryImpactRetainedGraph): Long
}

/** Conservative detached-storage estimates, never encoded response bytes or measured heap usage. */
@JvmInline
value class QueryCheckpointStorageBytes private constructor(val value: Long) {
    operator fun plus(other: QueryCheckpointStorageBytes): QueryCheckpointStorageBytes =
        QueryCheckpointStorageBytes(if (Long.MAX_VALUE - value < other.value) Long.MAX_VALUE else value + other.value)

    companion object {
        fun parse(value: Long): Refinement<QueryCheckpointStorageBytes, QueryCheckpointStorageFailure> =
            if (value < 0L) Refinement.Rejected(QueryCheckpointStorageFailure.NEGATIVE_BYTES)
            else Refinement.Refined(QueryCheckpointStorageBytes(value))
    }
}

enum class QueryCheckpointStorageFailure {
    NEGATIVE_BYTES
}

/** The five existing accounting owners remain separate; their saturated sum is the admission estimate. */
data class QueryCheckpointStorageEstimate(
    val tasks: QueryCheckpointStorageBytes,
    val identityRows: QueryCheckpointStorageBytes,
    val inputs: QueryCheckpointStorageBytes,
    val impact: QueryCheckpointStorageBytes,
    val joins: QueryCheckpointStorageBytes,
) {
    val required: QueryCheckpointStorageBytes
        get() = tasks + identityRows + inputs + impact + joins
}

enum class QueryCheckpointStorageOutcome {
    WITHIN_LIMIT,
    CAPACITY_EXCEEDED,
}

/** Outcome is derived from the exact estimate and allowance; callers cannot supply a contradictory outcome. */
class QueryCheckpointStorageAdmission
private constructor(
    val estimate: QueryCheckpointStorageEstimate,
    val allowance: QueryByteLimit,
) {
    val outcome: QueryCheckpointStorageOutcome =
        if (estimate.required.value > allowance.value) QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED
        else QueryCheckpointStorageOutcome.WITHIN_LIMIT

    companion object {
        fun evaluate(
            estimate: QueryCheckpointStorageEstimate,
            allowance: QueryByteLimit,
        ): QueryCheckpointStorageAdmission = QueryCheckpointStorageAdmission(estimate, allowance)
    }
}

/** Explicit, bounded observation capability; no selectors, authority payloads, paths or source text. */
fun interface QueryCheckpointStorageObservation {
    fun observe(admission: QueryCheckpointStorageAdmission)

    companion object {
        val None = QueryCheckpointStorageObservation {}
    }
}
