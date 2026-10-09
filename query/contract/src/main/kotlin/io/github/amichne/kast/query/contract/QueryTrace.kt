package io.github.amichne.kast.query.contract

/** Finite static trace roles prevent following downstream uses recursively without a depth bound. */
enum class QueryTracePhase {
    SEED,
    IMPLEMENTATION,
    USE,
}

enum class QueryGroupingEvidence {
    FIRST_ARRIVAL,
    ALL_ARRIVALS,
    /** Merge arrivals only when the complete scoped read capability is identical. */
    ALL_SCOPED_ARRIVALS,
}
