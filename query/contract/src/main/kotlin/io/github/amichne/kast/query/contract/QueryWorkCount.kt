package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

enum class QueryWorkCountFailure {
    NEGATIVE
}

@JvmInline
value class QueryWorkCount private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<QueryWorkCount, QueryWorkCountFailure> =
            if (raw < 0L) Refinement.Rejected(QueryWorkCountFailure.NEGATIVE)
            else Refinement.Refined(QueryWorkCount(raw))
    }
}

/** Absence of an execution receipt is never interpreted as zero work. */
sealed interface QueryWorkUsage {
    data object Unobserved : QueryWorkUsage

    data class Observed(val count: QueryWorkCount) : QueryWorkUsage
}
