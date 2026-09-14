package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryRunResultWireDocument(
    val items: List<QueryResultItemWireDocument>,
    val failures: List<QueryItemFailureWireDocument>,
    val continuation: String? = null,
    val terminalReason: QueryTerminalReasonWireDocument? = null,
)

@Serializable
internal enum class QueryTerminalReasonWireDocument {
    @SerialName("upstream-incomplete") UPSTREAM_INCOMPLETE,
    @SerialName("output-item-too-large") OUTPUT_ITEM_TOO_LARGE,
    @SerialName("checkpoint-capacity-exceeded") CHECKPOINT_CAPACITY_EXCEEDED,
    @SerialName("no-progress") NO_PROGRESS,
}

@Serializable
internal enum class QueryExecutionRejectionWireDocument {
    @SerialName("continuation-unavailable") CONTINUATION_UNAVAILABLE,
    @SerialName("continuation-mismatch") CONTINUATION_MISMATCH,
    @SerialName("request-rejected") REQUEST_REJECTED,
    @SerialName("discovery-rejected") DISCOVERY_REJECTED,
    @SerialName("reference-stale") REFERENCE_STALE,
    @SerialName("budget-rejected") BUDGET_REJECTED,
    @SerialName("internal-contract-violation") INTERNAL_CONTRACT_VIOLATION,
}

@Serializable
internal data class QueryRunQualificationWireDocument(
    val knownMinimum: Int,
    val limitations: List<QueryLimitationWireDocument>,
)

@Serializable
internal enum class QueryLimitationWireDocument {
    @SerialName("result-limit-reached") RESULT_LIMIT_REACHED,
    @SerialName("byte-limit-reached") BYTE_LIMIT_REACHED,
    @SerialName("work-limit-reached") WORK_LIMIT_REACHED,
    @SerialName("time-limit-reached") TIME_LIMIT_REACHED,
    @SerialName("discovery-incomplete") DISCOVERY_INCOMPLETE,
    @SerialName("refinement-incomplete") REFINEMENT_INCOMPLETE,
    @SerialName("visibility-incomplete") VISIBILITY_INCOMPLETE,
    @SerialName("relation-incomplete") RELATION_INCOMPLETE,
}
