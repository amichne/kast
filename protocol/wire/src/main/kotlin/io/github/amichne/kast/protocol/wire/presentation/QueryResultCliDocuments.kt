@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRowWindowDocument
import io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions
import io.github.amichne.kast.protocol.wire.RelationReferenceOccurrenceWireDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryCompleteCliDocument(
    @SerialName("original_evidence_summary")
    val originalEvidenceSummary: io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument?,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("deferred_evidence")
    val deferredEvidence: List<io.github.amichne.kast.protocol.contract.QueryDeferredDetailDocument> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val detail: io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument? = null,
    @kotlinx.serialization.Transient val originalItems: List<QueryResultItemDocument> = emptyList(),
    @kotlinx.serialization.Transient
    val presentationOutput: io.github.amichne.kast.protocol.contract.QueryOutputDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("relation_graph")
    val relationGraph: QueryRelationGraphCliDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val interpretation: io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument =
        io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument.QueryResult,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invocation: io.github.amichne.kast.protocol.contract.QueryInvocationDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("evidence_window")
    val evidenceWindow: io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument? = null,
    val question: io.github.amichne.kast.protocol.contract.QueryQuestionDocument,
    @SerialName("impact_accounting")
    val impactAccounting: io.github.amichne.kast.protocol.contract.ImpactAccountingDocument,
    val operation: QueryCliOperation,
    val status: QueryCompleteCliStatus,
    val items: List<QueryResultItemCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val failures: List<QueryItemFailureCliDocument>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val omissions: List<QueryRelationOmissionCliDocument>? = null,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    @SerialName("reference_observations") val referenceObservations: List<RelationReferenceOccurrenceWireDocument>,
    @SerialName("discovery_observations") val discoveryObservations: List<QueryDiscoveryObservationCliDocument>,
    @SerialName("relation_observations")
    val relationObservations: List<io.github.amichne.kast.protocol.wire.QueryRelationObservationWireDocument>,
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("row_window")
    val rowWindow: QueryRowWindowDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    @SerialName("page_progress") val pageProgress: QueryPageProgressCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
internal data class QueryQualifiedCliDocument(
    @SerialName("original_evidence_summary")
    val originalEvidenceSummary: io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument?,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("deferred_evidence")
    val deferredEvidence: List<io.github.amichne.kast.protocol.contract.QueryDeferredDetailDocument> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val detail: io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument? = null,
    @kotlinx.serialization.Transient val originalItems: List<QueryResultItemDocument> = emptyList(),
    @kotlinx.serialization.Transient
    val presentationOutput: io.github.amichne.kast.protocol.contract.QueryOutputDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("relation_graph")
    val relationGraph: QueryRelationGraphCliDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val interpretation: io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument =
        io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument.QueryResult,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invocation: io.github.amichne.kast.protocol.contract.QueryInvocationDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("evidence_window")
    val evidenceWindow: io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument? = null,
    val question: io.github.amichne.kast.protocol.contract.QueryQuestionDocument,
    @SerialName("impact_accounting")
    val impactAccounting: io.github.amichne.kast.protocol.contract.ImpactAccountingDocument,
    val operation: QueryCliOperation,
    val status: QueryQualifiedCliStatus,
    val items: List<QueryResultItemCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val failures: List<QueryItemFailureCliDocument>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val omissions: List<QueryRelationOmissionCliDocument>? = null,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    @SerialName("reference_observations") val referenceObservations: List<RelationReferenceOccurrenceWireDocument>,
    @SerialName("discovery_observations") val discoveryObservations: List<QueryDiscoveryObservationCliDocument>,
    @SerialName("relation_observations")
    val relationObservations: List<io.github.amichne.kast.protocol.wire.QueryRelationObservationWireDocument>,
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("row_window")
    val rowWindow: QueryRowWindowDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    @SerialName("page_progress") val pageProgress: QueryPageProgressCliDocument,
    val qualification: QueryQualificationCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(
        minimumLength = 1,
        maximumLength = MAXIMUM_QUERY_CLI_TEXT_LENGTH,
    )
    val continuation: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("terminal_reason")
    val terminalReason:
        @Serializable(with = QueryTerminalReasonCliSerializer::class)
        io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument? =
        null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
internal data class QueryQualificationCliDocument(
    val knownMinimum: Int,
    val limitations:
        List<
            @Serializable(with = QueryLimitationCliSerializer::class)
            io.github.amichne.kast.protocol.contract.QueryLimitationDocument
        >,
    val progress: QueryQualifiedProgressDocument,
)

private const val MAXIMUM_QUERY_CLI_TEXT_LENGTH = 1_048_576
