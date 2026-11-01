package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.EmptyReferenceObservations
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeferredDetailDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization

/** A chunk read reuses the selected immutable result and never invokes a semantic operation. */
internal fun fitHostedQueryEntryDetail(
    semantic: HostedQueryOutcome,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)? = null,
): HostedResponse {
    val payload =
        when (semantic) {
            is OperationOutcome.Complete -> semantic.evidence.payload
            is OperationOutcome.Qualified -> semantic.evidence.payload
            is OperationOutcome.Rejected ->
                return HostedResponse.Canonical.encode(
                    CanonicalOperationWireBindings.queryRun,
                    semantic,
                    limits,
                    maximumBytes,
                )
        }
    return when (val selected = HostedEntryDetailSelection.admit(payload)) {
        is Refinement.Rejected -> detailRejected(selected.failure, limits, maximumBytes)
        is Refinement.Refined ->
            HostedEntryDetailFitting(semantic, payload, selected.value, limits, maximumBytes, published).fit()
    }
}

private data class HostedEntryDetailSelection
private constructor(
    val selector: QueryEntryDetailSelectorDocument,
    val text: String,
) {
    companion object {
        fun admit(payload: QueryRunResult): Refinement<HostedEntryDetailSelection, QueryExecutionRejectionDocument> {
            val selector =
                payload.detailSelection ?: return Refinement.Rejected(QueryExecutionRejectionDocument.REQUEST_REJECTED)
            val retained = payload.retention as? QueryResultRetention.Retained
            if (retained?.reference != selector.result)
                return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
            val entry =
                when (val selected = selectEntry(payload, selector)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            if (entry.kind != selector.kind || entryDigest(entry.text) != selector.sha256.value)
                return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
            if (!validDetailOffset(entry.text, selector.offset.value))
                return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
            return Refinement.Refined(HostedEntryDetailSelection(selector, entry.text))
        }
    }
}

private fun selectEntry(
    payload: QueryRunResult,
    selector: QueryEntryDetailSelectorDocument,
): Refinement<QueryEntryDetailSerialization.Entry, QueryExecutionRejectionDocument> =
    when (selector.target) {
        QueryEntryDetailTarget.ROW ->
            when (val row = payload.items.values.singleOrNull()) {
                null -> Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
                else -> Refinement.Refined(QueryEntryDetailSerialization.row(row))
            }
        QueryEntryDetailTarget.EVIDENCE -> QueryEntryDetailSerialization.evidence(payload, 0)
    }

private fun validDetailOffset(text: String, offset: Int): Boolean =
    when {
        offset > text.length -> false
        offset == 0 || offset == text.length -> true
        else -> !splitsSupplementaryCharacter(text, offset)
    }

private fun splitsSupplementaryCharacter(text: String, offset: Int): Boolean =
    text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()

private class HostedEntryDetailFitting(
    private val semantic: HostedQueryOutcome,
    payload: QueryRunResult,
    private val selection: HostedEntryDetailSelection,
    private val limits: ReadLimits,
    private val maximumBytes: ReturnedByteLimit,
    private val published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
) {
    private val base = payload.detailBase()
    private val text = selection.text
    private val start = selection.selector.offset.value

    fun fit(): HostedResponse {
        var lower = start + if (start < text.length) 1 else 0
        var upper = minOf(text.length, start + MAX_DETAIL_FRAGMENT_CODE_UNITS)
        var best: Pair<HostedQueryOutcome, HostedResponse>? = null
        while (lower <= upper) {
            val midpoint = lower + (upper - lower) / 2
            val end = fragmentEnd(midpoint)
            if (end == start && start < text.length) {
                lower = start + SUPPLEMENTARY_CODE_UNITS
                continue
            }
            val encoded = encode(end)
            if (encoded.second is HostedResponse.Canonical<*, *, *>) {
                best = encoded
                lower = midpoint + 1
            } else upper = midpoint - 1
        }
        val fitted =
            best
                ?: return detailRejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE, limits, maximumBytes)
        return publish(fitted)
    }

    private fun fragmentEnd(midpoint: Int): Int =
        if (midpoint < text.length && midpoint > start && splitsSupplementaryCharacter(text, midpoint)) midpoint - 1
        else midpoint

    private fun encode(end: Int): Pair<HostedQueryOutcome, HostedResponse> {
        val detail =
            QueryEntryDetailDocument(
                selection.selector,
                ProtocolOffset.parse(end).detailValue(),
                ProtocolOffset.parse(text.length).detailValue(),
                ProtocolText.parse(text.substring(start, end)).detailValue(),
            )
        val outcome = semantic.withDetailPayload(base.copy(detail = detail))
        val encoded =
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, outcome, limits, maximumBytes)
        val response =
            if (encoded is HostedResponse.Oversized)
                HostedResponse.Canonical.queryPresentation(outcome, limits, maximumBytes, detail)
            else encoded
        return outcome to response
    }

    private fun publish(fitted: Pair<HostedQueryOutcome, HostedResponse>): HostedResponse {
        val response = fitted.second
        if (response is HostedResponse.Canonical<*, *, *>)
            published?.invoke(encodedPublication(fitted.first.publicationPage(), response))
        return response
    }
}

private fun HostedQueryOutcome.withDetailPayload(page: QueryRunResult): HostedQueryOutcome =
    when (this) {
        is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.copy(payload = page))
        is OperationOutcome.Qualified -> OperationOutcome.Qualified(evidence.copy(payload = page), qualification)
        is OperationOutcome.Rejected -> this
    }

private fun QueryRunResult.detailBase(): QueryRunResult {
    val empty = presentationPrefix(0).detailValue()
    val finalWindow = presentationWindow?.let {
        QueryRetainedPresentationWindow.create(it.reference, it.resultEnd, it.resultEnd, it.resultEnd).detailValue()
    }
    return empty.copy(
        failures = BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).detailValue(),
        omissions = QueryRelationOmissionDocument.Empty,
        walkObservations = QueryWalkObservationDocument.Empty,
        referenceObservations = EmptyReferenceObservations,
        discoveryObservations = QueryDiscoveryObservationDocument.Empty,
        relationObservations = QueryRelationObservationDocument.Empty,
        deferredEvidence = BoundedProtocolList.create(emptyList<QueryDeferredDetailDocument>()).detailValue(),
        detailSelection = null,
        presentationWindow = finalWindow,
        nextCursor = null,
        evidenceWindow = null,
    )
}

private const val MAX_DETAIL_FRAGMENT_CODE_UNITS = 32768
private const val SUPPLEMENTARY_CODE_UNITS = 2

private fun detailRejected(
    reason: QueryExecutionRejectionDocument,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
): HostedResponse =
    HostedResponse.Canonical.encode(
        CanonicalOperationWireBindings.queryRun,
        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason)),
        limits,
        maximumBytes,
    )
