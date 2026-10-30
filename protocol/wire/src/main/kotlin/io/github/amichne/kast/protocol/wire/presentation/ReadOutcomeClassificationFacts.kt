package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Exact facts from schema-admitted canonical output. The projection does not choose shell exit codes. */
class QueryClassificationFacts
internal constructor(
    val status: ReadOutcomeStatus,
    val coverage: QueryCoverageCliDocument?,
    val interpretation: QueryResultInterpretationDocument?,
    val stop: QueryInvocationStop?,
    val progress: QueryQualifiedProgressDocument?,
)

class SourceClassificationFacts
internal constructor(
    val status: ReadOutcomeStatus,
    val limitations: List<SourceReadLimitationDocument>,
    val progress: SourceQualifiedProgressDocument?,
)

@Serializable
enum class ReadOutcomeStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
    @SerialName("rejected") REJECTED,
}

internal fun queryClassificationFacts(document: JsonObject): QueryClassificationFacts {
    val facts = classificationJson.decodeFromJsonElement<QueryClassificationDocument>(document)
    return QueryClassificationFacts(
        facts.status,
        facts.coverage,
        facts.interpretation,
        facts.invocation?.stop ?: facts.rejection?.invocationStop(),
        facts.qualification?.progress,
    )
}

internal fun sourceClassificationFacts(document: JsonObject): SourceClassificationFacts {
    val facts = classificationJson.decodeFromJsonElement<SourceClassificationDocument>(document)
    val limitations =
        facts.qualification?.limitations.orEmpty().map { raw ->
            SourceReadLimitationDocument.entries.singleOrNull { it.cliName() == raw }
                ?: throw SerializationException("Unknown source read limitation")
        }
    return SourceClassificationFacts(facts.status, limitations, facts.qualification?.progress)
}

private fun QueryRejectionCliDocument.invocationStop(): QueryInvocationStop? =
    when (this) {
        is QueryRejectionCliDocument.CompletionUnproven -> detail.stop
        is QueryRejectionCliDocument.ExecutionRejected ->
            when (reason) {
                QueryExecutionRejectionDocument.INVOCATION_TIME_LIMIT -> QueryInvocationStop.TIME_LIMIT
                QueryExecutionRejectionDocument.INVOCATION_WORK_LIMIT,
                QueryExecutionRejectionDocument.INVOCATION_RETAINED_BYTES_LIMIT,
                QueryExecutionRejectionDocument.INVOCATION_CANCELLED,
                QueryExecutionRejectionDocument.RESULT_UNAVAILABLE,
                QueryExecutionRejectionDocument.RESULT_STALE_BASIS,
                QueryExecutionRejectionDocument.RESULT_ROW_UNAVAILABLE,
                QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE,
                QueryExecutionRejectionDocument.EVIDENCE_CURSOR_OUT_OF_RANGE,
                QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE,
                QueryExecutionRejectionDocument.TEXT_MATCH_LIMIT_EXCEEDED,
                QueryExecutionRejectionDocument.RIGHT_INPUT_INCOMPLETE,
                QueryExecutionRejectionDocument.UNKNOWN_BINDING_NAME,
                QueryExecutionRejectionDocument.OUTPUT_KIND_MISMATCH,
                QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE,
                QueryExecutionRejectionDocument.CONTINUATION_MISMATCH,
                QueryExecutionRejectionDocument.CONTINUATION_STALE_BASIS,
                QueryExecutionRejectionDocument.CONTINUATION_IN_USE,
                QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED,
                QueryExecutionRejectionDocument.CONTINUATION_OWNER_RETIRED,
                QueryExecutionRejectionDocument.CONTINUATION_CLAIM_UNAVAILABLE,
                QueryExecutionRejectionDocument.CONTINUATION_EXPIRED,
                QueryExecutionRejectionDocument.CONTINUATION_EVICTED,
                QueryExecutionRejectionDocument.CONTINUATION_DEPENDENCY_UNAVAILABLE,
                QueryExecutionRejectionDocument.PUBLISHED_PAGE_MISMATCH,
                QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION,
                QueryExecutionRejectionDocument.REQUEST_REJECTED,
                QueryExecutionRejectionDocument.DISCOVERY_REJECTED,
                QueryExecutionRejectionDocument.REFERENCE_STALE,
                QueryExecutionRejectionDocument.BUDGET_REJECTED,
                QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION -> null
            }
        is QueryRejectionCliDocument.CompletionUnsupported,
        is QueryRejectionCliDocument.ReferenceRejected,
        is QueryRejectionCliDocument.SourceRejected,
        is QueryRejectionCliDocument.ImpactExecutionRejected,
        is QueryRejectionCliDocument.ImpactSourceRejected,
        is QueryRejectionCliDocument.ImpactPresentationRejected,
        QueryRejectionCliDocument.WorkspaceNotReady -> null
    }

@Serializable
private data class QueryClassificationDocument(
    val status: ReadOutcomeStatus,
    val coverage: QueryCoverageCliDocument? = null,
    val interpretation: QueryResultInterpretationDocument? = null,
    val invocation: QueryInvocationDocument? = null,
    val qualification: QueryQualificationCliDocument? = null,
    val rejection: QueryRejectionCliDocument? = null,
)

@Serializable
private data class SourceClassificationDocument(
    val status: ReadOutcomeStatus,
    val qualification: SourceReadQualificationCliDocument? = null,
)

private val classificationJson = Json { ignoreUnknownKeys = true }
