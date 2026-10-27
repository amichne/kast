package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryPreviewDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.presentationUnitCount
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge

/** A full-envelope fit of retained rows and independently paged evidence changes presentation alone. */
internal fun fitHostedAutomaticQueryPreview(
    semantic: HostedQueryOutcome,
    limits: ReadLimits,
    maximumRows: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    published: ((QueryPublicationPageCharge.Encoded) -> Unit)?,
): HostedResponse {
    val evidence =
        when (semantic) {
            is OperationOutcome.Complete -> semantic.evidence
            is OperationOutcome.Qualified -> semantic.evidence
            is OperationOutcome.Rejected ->
                return HostedResponse.Canonical.encode(
                    CanonicalOperationWireBindings.queryRun,
                    semantic,
                    limits,
                    maximumBytes,
                )
        }
    return HostedAutomaticQueryPreview(semantic, evidence, limits, maximumRows, maximumBytes, published).fit()
}

private class HostedAutomaticQueryPreview(
    private val semantic: HostedQueryOutcome,
    private val evidence: EvidenceEnvelope<QueryRunResult>,
    private val limits: ReadLimits,
    private val maximumRows: ResultLimit,
    private val maximumBytes: ReturnedByteLimit,
    private val published: ((QueryPublicationPageCharge.Encoded) -> Unit)?,
) {
    private var original = boundedRows(evidence.payload)
    private val invocation = original.invocation

    private fun boundedRows(payload: QueryRunResult): QueryRunResult =
        when (val selected = payload.presentationPrefix(minOf(payload.items.values.size, maximumRows.value))) {
            is Refinement.Refined ->
                payload.copy(
                    items = selected.value.items,
                    nextCursor = selected.value.nextCursor,
                    presentationWindow = selected.value.presentationWindow,
                )
            is Refinement.Rejected -> error("Retained rows lost their admitted presentation window")
        }

    private fun encode(count: Int): Pair<HostedQueryOutcome, HostedResponse> {
        val window =
            when (val selected = original.presentationPrefix(count)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected ->
                    return semantic to HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
            }
        val rows = window.items.values
        val bytes = CanonicalQueryCliDocuments.previewBytes(rows)
        val preview = invocation?.let {
            if (rows.size == it.accumulatedRowCount) QueryPreviewDocument.Inline(rows.size, bytes)
            else QueryPreviewDocument.Prefix(rows.size, bytes)
        }
        val evidenceWindow = window.evidenceWindow
        val payload =
            window.copy(
                evidenceWindow = evidenceWindow,
                invocation =
                    when (val admitted = preview?.let { invocation?.withPreview(it) }) {
                        null -> null
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return semantic to HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
                    },
            )
        val page: HostedQueryOutcome =
            when (semantic) {
                is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.copy(payload = payload))
                is OperationOutcome.Qualified ->
                    OperationOutcome.Qualified(evidence.copy(payload = payload), semantic.qualification)
                is OperationOutcome.Rejected<*> -> error("Rejected invocation was handled before preview fitting")
            }
        return page to
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, page, limits, maximumBytes)
    }

    private fun prepareDeferrals() {
        if (original.retention !is io.github.amichne.kast.protocol.contract.QueryResultRetention.Retained) return
        deferOversizedRows()
        deferOversizedEvidence()
        if (original.presentationUnitCount > 0 && encode(1).second is HostedResponse.Oversized) deferFirstUnit()
    }

    private fun deferOversizedRows() {
        for (index in original.items.values.indices) {
            if (
                io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization.row(original.items.values[index])
                    .text
                    .toByteArray(Charsets.UTF_8)
                    .size > maximumBytes.value
            )
                original = original.deferredRow(index)
        }
    }

    private fun deferOversizedEvidence() {
        val count = original.presentationUnitCount - original.items.values.size
        for (index in count - 1 downTo 0) {
            val entry = io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization.evidence(original, index)
            if (entry is Refinement.Refined && entry.value.text.toByteArray(Charsets.UTF_8).size > maximumBytes.value)
                original = original.deferredEvidence(index, entry.value)
        }
    }

    private fun deferFirstUnit() {
        if (original.items.values.isNotEmpty()) {
            original = original.deferredRow(0)
            return
        }
        val start = original.evidenceWindow?.start?.value ?: 0
        if (original.deferredEvidence.values.any { it.selector.ordinal.value == start }) return
        val first = io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization.evidence(original, 0)
        if (first is Refinement.Refined) original = original.deferredEvidence(0, first.value)
    }

    fun fit(): HostedResponse {
        prepareDeferrals()
        // A retained read must advance a cursor; an initial invocation may return an empty preview.
        val minimumPrefix = if (invocation == null && original.presentationUnitCount > 0) 1 else 0
        var lower = minimumPrefix
        var upper = original.presentationUnitCount
        var best: Pair<HostedQueryOutcome, HostedResponse>? = null
        while (lower <= upper) {
            val count = lower + (upper - lower) / 2
            val candidate = encode(count)
            when (candidate.second) {
                is HostedResponse.Canonical<*, *, *> -> {
                    best = candidate
                    lower = count + 1
                }
                is HostedResponse.Oversized -> upper = count - 1
                else -> return candidate.second
            }
        }
        val fitted = best ?: return encode(minimumPrefix).second
        val response = fitted.second
        if (response is HostedResponse.Canonical<*, *, *>)
            published?.invoke(encodedPublication(fitted.first.publicationPage(), response))
        return response
    }
}
