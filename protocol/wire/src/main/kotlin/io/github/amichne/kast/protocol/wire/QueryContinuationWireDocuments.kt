@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryRunResultWireDocument(
    val items: List<QueryResultItemWireDocument>,
    val failures: List<QueryItemFailureWireDocument>,
    val omissions: List<QueryRelationOmissionWireDocument>,
    val retention: io.github.amichne.kast.protocol.contract.QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: io.github.amichne.kast.protocol.contract.QueryResultCursor? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
)

@Serializable
internal enum class QueryExecutionRejectionWireDocument {
    @SerialName("result-unavailable") RESULT_UNAVAILABLE,
    @SerialName("result-stale-basis") RESULT_STALE_BASIS,
    @SerialName("result-row-unavailable") RESULT_ROW_UNAVAILABLE,
    @SerialName("result-cursor-out-of-range") RESULT_CURSOR_OUT_OF_RANGE,
    @SerialName("result-field-unavailable") RESULT_FIELD_UNAVAILABLE,
    @SerialName("right-input-incomplete") RIGHT_INPUT_INCOMPLETE,
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
    val progress: io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument,
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
    @SerialName("source-incomplete") SOURCE_INCOMPLETE,
    @SerialName("relation-incomplete") RELATION_INCOMPLETE,
    @SerialName("row-selection-incomplete") ROW_SELECTION_INCOMPLETE,
}
