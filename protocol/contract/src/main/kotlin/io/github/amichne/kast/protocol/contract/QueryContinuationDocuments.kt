package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QueryTerminalReasonDocument {
    @SerialName("upstream-incomplete") UPSTREAM_INCOMPLETE,
    @SerialName("output-item-too-large") OUTPUT_ITEM_TOO_LARGE,
    @SerialName("checkpoint-capacity-exceeded") CHECKPOINT_CAPACITY_EXCEEDED,
    @SerialName("no-progress") NO_PROGRESS,
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
