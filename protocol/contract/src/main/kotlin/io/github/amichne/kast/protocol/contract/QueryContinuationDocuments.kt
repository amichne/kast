package io.github.amichne.kast.protocol.contract

enum class QueryTerminalReasonDocument {
    UPSTREAM_INCOMPLETE,
    OUTPUT_ITEM_TOO_LARGE,
    CHECKPOINT_CAPACITY_EXCEEDED,
    NO_PROGRESS,
}

enum class QueryExecutionRejectionDocument {
    CONTINUATION_UNAVAILABLE,
    CONTINUATION_MISMATCH,
    REQUEST_REJECTED,
    DISCOVERY_REJECTED,
    REFERENCE_STALE,
    BUDGET_REJECTED,
    INTERNAL_CONTRACT_VIOLATION,
}
