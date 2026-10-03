package io.github.amichne.kast.query.contract

enum class QueryRetainedResultFailure {
    EXECUTION_REJECTED,
    PRESENTATION_ONLY_ROWS,
    BASIS_MISMATCH,
    INCONSISTENT_COVERAGE,
    UNKNOWN_ROW,
    DUPLICATE_ROW,
}
