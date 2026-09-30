package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryPresentationWindowFailure
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow

/** Existing retained windows survive projection; a newly retained result establishes its whole initial slice. */
internal fun QueryResultRetention.presentationWindow(
    existing: QueryRetainedPresentationWindow?,
    count: Int,
): Refinement<QueryRetainedPresentationWindow?, QueryPresentationWindowFailure> {
    return when (this) {
        is QueryResultRetention.Retained -> {
            if (existing != null) {
                if (existing.reference != reference)
                    return Refinement.Rejected(QueryPresentationWindowFailure.RESULT_REFERENCE_MISMATCH)
                if (existing.itemCount != count)
                    return Refinement.Rejected(QueryPresentationWindowFailure.ITEM_WINDOW_MISMATCH)
                Refinement.Refined(existing)
            } else {
                val end =
                    QueryResultCursor.parse(count).refinedForQueryOrNull()
                        ?: return Refinement.Rejected(QueryPresentationWindowFailure.INVALID_ITEM_COUNT)
                QueryRetainedPresentationWindow.create(reference, QueryResultCursor.Start, end, end)
            }
        }
        QueryResultRetention.NotRequested,
        QueryResultRetention.CapacityExceeded -> Refinement.Refined(null)
    }
}
