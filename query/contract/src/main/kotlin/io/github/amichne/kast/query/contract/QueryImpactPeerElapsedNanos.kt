package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

enum class QueryImpactPeerElapsedNanosFailure {
    NEGATIVE
}

@JvmInline
value class QueryImpactPeerElapsedNanos private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<QueryImpactPeerElapsedNanos, QueryImpactPeerElapsedNanosFailure> =
            if (raw < 0L) Refinement.Rejected(QueryImpactPeerElapsedNanosFailure.NEGATIVE)
            else Refinement.Refined(QueryImpactPeerElapsedNanos(raw))
    }
}
