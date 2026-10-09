package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryPresentationWindowFailure
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow

/** Retained projection validates supplied bounds without manufacturing an initial result window. */
internal fun QueryResultRetention.presentationWindow(
    existing: QueryRetainedPresentationWindow?,
    count: Int,
): Refinement<QueryRetainedPresentationWindow?, QueryPresentationWindowFailure> {
    return when (this) {
        is QueryResultRetention.Retained -> {
            val window = existing ?: return Refinement.Rejected(QueryPresentationWindowFailure.MISSING_RETAINED_WINDOW)
            when {
                window.reference != reference ->
                    Refinement.Rejected(QueryPresentationWindowFailure.RESULT_REFERENCE_MISMATCH)
                window.itemCount != count -> Refinement.Rejected(QueryPresentationWindowFailure.ITEM_WINDOW_MISMATCH)
                else -> Refinement.Refined(window)
            }
        }
        QueryResultRetention.NotRequested ->
            if (existing == null) Refinement.Refined(null)
            else Refinement.Rejected(QueryPresentationWindowFailure.WINDOW_WITHOUT_RETAINED_RESULT)
    }
}
