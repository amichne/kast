package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryPreviewDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge

/** A full-envelope fit of an already retained automatic result changes presentation alone. */
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
    private val original = evidence.payload
    private val invocation = original.invocation ?: error("Automatic preview requires its invocation proof")

    private fun encode(count: Int): Pair<HostedQueryOutcome, HostedResponse> {
        val window =
            when (val selected = original.presentationPrefix(count)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected ->
                    return semantic to HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
            }
        val rows = original.items.values.take(count)
        val bytes =
            CanonicalQueryCliDocuments.symbolPreviewBytes(rows.filterIsInstance<QueryResultItemDocument.ExactSymbol>())
        val preview =
            if (count == invocation.accumulatedRowCount) QueryPreviewDocument.Inline(count, bytes)
            else QueryPreviewDocument.Prefix(count, bytes)
        val payload =
            original.copy(
                items = window.items,
                nextCursor = window.nextCursor,
                presentationWindow = window.presentationWindow,
                invocation =
                    when (val admitted = invocation.withPreview(preview)) {
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

    fun fit(): HostedResponse {
        var lower = 0
        var upper = minOf(original.items.values.size, maximumRows.value)
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
        val fitted = best ?: return encode(0).second
        val response = fitted.second
        if (response is HostedResponse.Canonical<*, *, *>)
            published?.invoke(encodedPublication(fitted.first.publicationPage(), response))
        return response
    }
}
