@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryRunResultWireDocument(
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invocation: io.github.amichne.kast.protocol.contract.QueryInvocationDocument? = null,
    val question: io.github.amichne.kast.protocol.contract.QueryQuestionDocument,
    @SerialName("impact_accounting")
    val impactAccounting: io.github.amichne.kast.protocol.contract.ImpactAccountingDocument,
    val items: List<QueryResultItemWireDocument>,
    val failures: List<QueryItemFailureWireDocument>,
    val omissions: List<QueryRelationOmissionWireDocument>,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationWireDocument>,
    @SerialName("reference_observations")
    val referenceObservations: List<RelationReferenceOccurrenceWireDocument> = emptyList(),
    @SerialName("discovery_observations")
    val discoveryObservations: List<io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument> =
        emptyList(),
    @SerialName("relation_observations")
    val relationObservations: List<QueryRelationObservationWireDocument> = emptyList(),
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
    @SerialName("text-match-limit-exceeded") TEXT_MATCH_LIMIT_EXCEEDED,
    @SerialName("right-input-incomplete") RIGHT_INPUT_INCOMPLETE,
    @SerialName("unknown-binding-name") UNKNOWN_BINDING_NAME,
    @SerialName("output-kind-mismatch") OUTPUT_KIND_MISMATCH,
    @SerialName("continuation-unavailable") CONTINUATION_UNAVAILABLE,
    @SerialName("continuation-mismatch") CONTINUATION_MISMATCH,
    @SerialName("continuation-stale-basis") CONTINUATION_STALE_BASIS,
    @SerialName("continuation-in-use") CONTINUATION_IN_USE,
    @SerialName("continuation-capacity-exceeded") CONTINUATION_CAPACITY_EXCEEDED,
    @SerialName("continuation-owner-retired") CONTINUATION_OWNER_RETIRED,
    @SerialName("continuation-claim-unavailable") CONTINUATION_CLAIM_UNAVAILABLE,
    @SerialName("continuation-expired") CONTINUATION_EXPIRED,
    @SerialName("continuation-evicted") CONTINUATION_EVICTED,
    @SerialName("continuation-dependency-unavailable") CONTINUATION_DEPENDENCY_UNAVAILABLE,
    @SerialName("published-page-mismatch") PUBLISHED_PAGE_MISMATCH,
    @SerialName("non-advancing-continuation") NON_ADVANCING_CONTINUATION,
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
    @SerialName("traversal-incomplete") TRAVERSAL_INCOMPLETE,
    @SerialName("row-selection-incomplete") ROW_SELECTION_INCOMPLETE,
    @SerialName("IMPACT_COVERAGE_UNPROVEN") IMPACT_COVERAGE_UNPROVEN,
    @SerialName("retention-limit-reached") RETENTION_LIMIT_REACHED,
    @SerialName("execution-incomplete") EXECUTION_INCOMPLETE,
}
