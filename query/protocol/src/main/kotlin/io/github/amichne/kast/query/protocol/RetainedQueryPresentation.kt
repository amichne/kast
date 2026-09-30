package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryResult

/** One admitted immutable presentation slice retains the exact rows, coverage and offset witness together. */
@ConsistentCopyVisibility
internal data class RetainedQueryPresentation
private constructor(
    val result: QueryResult,
    val coverage: QueryCoverage.Qualified?,
    val rowIds: List<QueryResultRowReference>,
    val window: QueryRetainedPresentationWindow,
) {
    companion object {
        fun create(
            restored: QueryResultRestoration.Restored,
            request: QueryRunRequest.ReadResult,
        ): Refinement<RetainedQueryPresentation, QueryExecutionRejectionDocument> {
            val rowCount = restored.result.rowCount
            val start = request.cursor.value
            if (start > rowCount) return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
            val end = minOf(start + RESULT_PAGE_SIZE, rowCount)
            val rows =
                retainedRows(restored.result, request.output, start, end)
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
            val window =
                QueryRetainedPresentationWindow.create(
                        request.result,
                        request.cursor,
                        QueryResultCursor.parse(end).refinedForQueryOrNull() ?: return contractRejected(),
                        QueryResultCursor.parse(rowCount).refinedForQueryOrNull() ?: return contractRejected(),
                    )
                    .refinedForQueryOrNull() ?: return contractRejected()
            val result =
                QueryResult(
                    rows,
                    restored.result.failures,
                    restored.result.omissions,
                    restored.result.walkObservations,
                    referenceObservations = restored.result.referenceObservations,
                    discoveryObservations = restored.result.discoveryObservations,
                )
            return Refinement.Refined(
                RetainedQueryPresentation(
                    result,
                    restored.result.coverage as? QueryCoverage.Qualified,
                    restored.rowIds.subList(start, end),
                    window,
                )
            )
        }

        private fun contractRejected(): Refinement.Rejected<QueryExecutionRejectionDocument> =
            Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
    }
}

private const val RESULT_PAGE_SIZE = 100
