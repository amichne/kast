package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.query.contract.QueryRetainedRowOrdinal
import java.util.Collections

/** Presentation selection preserves the full existing store issuance alongside selected original identities. */
internal sealed interface QueryPresentedResultIssuance {
    data object NotRequested : QueryPresentedResultIssuance

    data object Unavailable : QueryPresentedResultIssuance

    data object CapacityExceeded : QueryPresentedResultIssuance

    class Issued
    private constructor(
        val original: QueryResultIssuance.Issued,
        val presentedRowIds: List<QueryResultRowReference>,
        val selection: QueryPresentedWindowSelection,
    ) : QueryPresentedResultIssuance {
        companion object {
            fun admit(
                original: QueryResultIssuance.Issued,
                ordinals: List<QueryRetainedRowOrdinal>,
            ): Refinement<Issued, QueryExecutionRejectionDocument> {
                if (ordinals.any { it.value !in original.rowIds.indices })
                    return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
                val rowIds = Collections.unmodifiableList(ordinals.map { original.rowIds[it.value] })
                val selection =
                    when (val admitted = QueryPresentedWindowSelection.admit(original, rowIds)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                return Refinement.Refined(Issued(original, rowIds, selection))
            }
        }
    }
}
