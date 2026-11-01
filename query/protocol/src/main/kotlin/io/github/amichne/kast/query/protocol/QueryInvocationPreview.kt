package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryOriginalFailureDocument
import io.github.amichne.kast.protocol.contract.QueryPreviewDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunRejection

internal data class QueryInvocationPreview(
    val items: BoundedProtocolList<QueryResultItemDocument>,
    val retention: QueryResultRetention,
    val window: QueryRetainedPresentationWindow?,
    val summary: QueryInvocationDocument,
)

/** Pure fitting after the invocation owner issues row identities. */
internal fun QueryInvocationPolicy.fitPreview(
    candidates: List<QueryResultItemDocument>,
    issuance: QueryResultIssuance.Issued?,
    count: Int,
    stop: QueryInvocationStop,
    failure: QueryOriginalFailureDocument?,
): Refinement<QueryInvocationPreview, QueryRunRejection> {
    var end = 0
    while (end < candidates.size && previewBytes(candidates.take(end + 1)) <= previewBytesLimit.value) end++
    val rows = candidates.take(end)
    val bytes = previewBytes(rows)
    if (bytes !in 2..previewBytesLimit.value)
        return Refinement.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        )
    val retention = issuance?.let { QueryResultRetention.Retained(it.reference) } ?: QueryResultRetention.NotRequested
    val window = issuance?.let { issued ->
        QueryRetainedPresentationWindow.create(
                issued.reference,
                QueryResultCursor.Start,
                QueryResultCursor.parse(end).required(),
                QueryResultCursor.parse(count).required(),
            )
            .required()
    }
    val summary =
        QueryInvocationDocument.create(
                count = count,
                preview =
                    if (end == count) QueryPreviewDocument.Inline(end, bytes)
                    else QueryPreviewDocument.Prefix(end, bytes),
                stop = stop,
                failure = failure,
            )
            .required()
    return Refinement.Refined(
        QueryInvocationPreview(
            BoundedProtocolList.create<QueryResultItemDocument>(rows).required(),
            retention,
            window,
            summary,
        )
    )
}
