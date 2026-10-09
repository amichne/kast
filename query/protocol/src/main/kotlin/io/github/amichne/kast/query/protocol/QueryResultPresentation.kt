package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection

internal data class PresentedQueryRows(
    val items: BoundedProtocolList<QueryResultItemDocument>,
    val retention: QueryResultRetention,
)

/** Projects supplied identities; only the completed invocation issues retained results. */
internal object QueryResultPresentation {
    fun present(
        items: List<QueryResultItemDocument>,
        retention: QueryResultRetention,
        rowIds: List<QueryResultRowReference>?,
    ): Refinement<PresentedQueryRows, QueryRunRejection> {
        if (retention is QueryResultRetention.Retained && rowIds == null) return presentationRejected()
        val identified =
            when (val projected = identifyRows(items, rowIds)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return presentationRejected()
            }
        val bounded = BoundedProtocolList.create(identified).refinedForQueryOrNull() ?: return presentationRejected()
        return Refinement.Refined(PresentedQueryRows(bounded, retention))
    }

    private fun presentationRejected(): Refinement.Rejected<QueryRunRejection> =
        Refinement.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        )
}
