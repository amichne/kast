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
    RESULT_UNAVAILABLE,
    RESULT_STALE_BASIS,
    RESULT_ROW_UNAVAILABLE,
    RESULT_CURSOR_OUT_OF_RANGE,
    RESULT_FIELD_UNAVAILABLE,
    RIGHT_INPUT_INCOMPLETE,
    DUPLICATE_BINDING_NAME,
    UNKNOWN_BINDING_NAME,
    OUTPUT_KIND_MISMATCH,
    CONTINUATION_UNAVAILABLE,
    CONTINUATION_MISMATCH,
    REQUEST_REJECTED,
    DISCOVERY_REJECTED,
    REFERENCE_STALE,
    BUDGET_REJECTED,
    INTERNAL_CONTRACT_VIOLATION,
}
