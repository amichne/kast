package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow

/** Only a contiguous original prefix proves a next cursor into the original result. */
internal sealed interface QueryPresentedWindowSelection {
    data object NotRetained : QueryPresentedWindowSelection

    class NonContiguous(val window: QueryRetainedPresentationWindow) : QueryPresentedWindowSelection

    class Contiguous(val window: QueryRetainedPresentationWindow) : QueryPresentedWindowSelection

    companion object {
        fun admit(
            original: QueryResultIssuance.Issued,
            rowIds: List<QueryResultRowReference>,
        ): Refinement<QueryPresentedWindowSelection, QueryExecutionRejectionDocument> {
            val end =
                when (val cursor = QueryResultCursor.parse(rowIds.size)) {
                    is Refinement.Refined -> cursor.value
                    is Refinement.Rejected -> return rejected()
                }
            if (rowIds != original.rowIds.take(rowIds.size))
                return Refinement.Refined(
                    NonContiguous(QueryRetainedPresentationWindow.nonContiguous(original.reference, end))
                )
            val resultEnd =
                when (val cursor = QueryResultCursor.parse(original.rowIds.size)) {
                    is Refinement.Refined -> cursor.value
                    is Refinement.Rejected -> return rejected()
                }
            return when (
                val window =
                    QueryRetainedPresentationWindow.create(
                        reference = original.reference,
                        start = QueryResultCursor.Start,
                        end = end,
                        resultEnd = resultEnd,
                    )
            ) {
                is Refinement.Refined -> Refinement.Refined(Contiguous(window.value))
                is Refinement.Rejected -> rejected()
            }
        }

        private fun rejected() = Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
    }
}
