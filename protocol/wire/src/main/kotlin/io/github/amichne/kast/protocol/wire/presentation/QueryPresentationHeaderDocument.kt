package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthority
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityLive
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityLiveDigest
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityLiveDigestContentView
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityUnavailable
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOriginalOutcome
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentPresentationDetail
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentPresentationDetailRead
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryReadResult
import io.github.amichne.kast.protocol.contract.QueryWorkspaceDigest
import io.github.amichne.kast.protocol.contract.reason
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
enum class QueryPresentationHeaderType {
    QUERY_PRESENTATION_HEADER
}

@Serializable
enum class QueryPresentationHeaderOperation {
    @SerialName("query.run") QUERY_RUN
}

@Serializable
enum class QueryPresentationHeaderStop {
    BYTE_LIMIT,
    DETAIL_SECTION,
}

@Serializable
enum class QueryPresentationHeaderStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
    @SerialName("rejected") REJECTED,
}

/** Native presentation control. Its original semantic result remains under the existing retained-result owner. */
@Serializable
data class QueryPresentationHeaderDocument(
    val semantic: QuerySemanticHeaderDocument,
    val binding: QuerySemanticHeaderDocumentAuthority,
    val nextQuery: QueryCompletionPublicReadQuery?,
    val detail: QueryEntryDetailDocument? = null,
    @Required val type: QueryPresentationHeaderType = QueryPresentationHeaderType.QUERY_PRESENTATION_HEADER,
    @Required val operation: QueryPresentationHeaderOperation = QueryPresentationHeaderOperation.QUERY_RUN,
    @Required
    @SerialName("delivery_stop")
    val deliveryStop: QueryPresentationHeaderStop = QueryPresentationHeaderStop.BYTE_LIMIT,
    val status: QueryPresentationHeaderStatus = semantic.originalOutcome.presentationStatus(),
) {
    fun hasConsistentOutcome(): Boolean = status == semantic.originalOutcome.presentationStatus()

    fun project(): ProjectedOperationOutcome {
        val document = CanonicalJsonDocument.generated(serializer()).create(this)
        return when (status) {
            QueryPresentationHeaderStatus.COMPLETE -> ProjectedOperationOutcome.Complete(document)
            QueryPresentationHeaderStatus.QUALIFIED -> ProjectedOperationOutcome.Qualified(document)
            QueryPresentationHeaderStatus.REJECTED -> ProjectedOperationOutcome.Rejected(document)
        }
    }

    companion object {
        fun from(
            outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>,
            detail: QueryEntryDetailDocument? = null,
        ): QueryPresentationHeaderDocument = HeaderPresentationProjection(outcome, detail).document()
    }
}

private class HeaderPresentationProjection(
    private val outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>,
    private val detail: QueryEntryDetailDocument?,
) {
    private val header = originalHeader()
    private val payload =
        when (outcome) {
            is OperationOutcome.Complete -> outcome.evidence.payload
            is OperationOutcome.Qualified -> outcome.evidence.payload
            is OperationOutcome.Rejected -> null
        }

    fun document(): QueryPresentationHeaderDocument {
        val binding = binding()
        val presented = detailPresentation()
        return QueryPresentationHeaderDocument(
            if (presented.authority is QuerySemanticHeaderDocumentAuthorityLive) presented.copy(authority = binding)
            else presented,
            binding,
            nextQuery(),
            detail,
            deliveryStop =
                if (detail == null) QueryPresentationHeaderStop.BYTE_LIMIT
                else QueryPresentationHeaderStop.DETAIL_SECTION,
        )
    }

    private fun originalHeader(): QuerySemanticHeaderDocument =
        when (val projected = CanonicalQueryCliDocuments.project(outcome)) {
            is ProjectedOperationOutcome.Complete ->
                header(projected.document, QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE)
            is ProjectedOperationOutcome.Qualified ->
                header(projected.document, QuerySemanticHeaderDocumentOriginalOutcome.QUALIFIED)
            is ProjectedOperationOutcome.Rejected ->
                header(projected.document, QuerySemanticHeaderDocumentOriginalOutcome.REJECTED_DOCUMENT)
        }

    private fun header(
        document: CanonicalJsonDocument,
        kind: QuerySemanticHeaderDocumentOriginalOutcome,
    ): QuerySemanticHeaderDocument = querySemanticHeader(Json.parseToJsonElement(document.value).jsonObject, kind)

    private fun binding(): QuerySemanticHeaderDocumentAuthority =
        when (outcome) {
            is OperationOutcome.Complete -> binding(outcome.evidence.basis)
            is OperationOutcome.Qualified -> binding(outcome.evidence.basis)
            is OperationOutcome.Rejected -> QuerySemanticHeaderDocumentAuthorityUnavailable
        }

    private fun binding(basis: EvidenceBasis): QuerySemanticHeaderDocumentAuthority =
        when (basis) {
            is EvidenceBasis.Live ->
                QuerySemanticHeaderDocumentAuthorityLiveDigest(
                    QueryWorkspaceDigest.fromRoot(basis.evidence.workspaceRoot),
                    ProtocolText.parse(basis.evidence.host.toString()).proven(),
                    basis.evidence.epoch,
                    QuerySemanticHeaderDocumentAuthorityLiveDigestContentView.SAVED_PSI_COMMITTED,
                    basis.evidence.version.toLong(),
                )
            is EvidenceBasis.Published -> QuerySemanticHeaderDocumentAuthorityUnavailable
        }

    private fun output(): QueryOutputDocument? =
        when (outcome) {
            is OperationOutcome.Complete ->
                outcome.evidence.payload.presentationOutput ?: outcome.evidence.payload.question.output
            is OperationOutcome.Qualified ->
                outcome.evidence.payload.presentationOutput ?: outcome.evidence.payload.question.output
            is OperationOutcome.Rejected ->
                ((outcome.reason.reason() as? QueryRunRejection.CompletionUnproven)?.evidence
                        as? QueryCompletionEvidenceDocument.Retained)
                    ?.question
                    ?.output
        }

    private fun nextQuery(): QueryCompletionPublicReadQuery? {
        val retained = (header.recovery as? QuerySemanticHeaderDocumentRecoveryReadResult)?.result
        val output = output()
        return if (retained == null || output == null) null
        else selectedRead(retained, output.toCompletionPublicOutput())
    }

    private fun selectedRead(
        retained: QueryResultReference,
        output: QueryCompletionPublicReadOutput,
    ): QueryCompletionPublicReadQuery? =
        when (val selected = detail) {
            null ->
                QueryCompletionPublicReadQuery(
                    QueryCompletionPublicReadRequest.rows(
                        retained,
                        output,
                        payload?.presentationWindow?.start ?: QueryResultCursor.Start,
                        payload?.evidenceWindow?.start,
                    )
                )
            else ->
                if (selected.end.value < selected.total.value)
                    QueryCompletionPublicReadQuery(
                        QueryCompletionPublicReadRequest.detail(
                            retained,
                            output,
                            selected.selector.copy(offset = selected.end),
                        )
                    )
                else null
        }

    private fun detailPresentation(): QuerySemanticHeaderDocument =
        when (val selected = detail) {
            null -> header
            else ->
                header.copy(
                    presentation =
                        header.presentation.copy(
                            detail = QuerySemanticHeaderDocumentPresentationDetail.DEFERRED_DETAIL,
                            detailRead =
                                if (selected.end.value == selected.total.value)
                                    QuerySemanticHeaderDocumentPresentationDetailRead.FINAL_FRAGMENT
                                else QuerySemanticHeaderDocumentPresentationDetailRead.FRAGMENT,
                        )
                )
        }
}

private fun QuerySemanticHeaderDocumentOriginalOutcome.presentationStatus(): QueryPresentationHeaderStatus =
    when (this) {
        QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE -> QueryPresentationHeaderStatus.COMPLETE
        QuerySemanticHeaderDocumentOriginalOutcome.QUALIFIED -> QueryPresentationHeaderStatus.QUALIFIED
        QuerySemanticHeaderDocumentOriginalOutcome.REJECTED_DOCUMENT,
        QuerySemanticHeaderDocumentOriginalOutcome.REJECTED -> QueryPresentationHeaderStatus.REJECTED
    }

private fun <T, E> Refinement<T, E>.proven(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted query header lost its basis text")
    }
