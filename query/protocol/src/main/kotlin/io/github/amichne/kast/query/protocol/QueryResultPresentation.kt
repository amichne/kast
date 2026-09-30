package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal data class PresentedQueryRows(
    val items: BoundedProtocolList<QueryResultItemDocument>,
    val retention: QueryResultRetention,
)

/** Retention issuance and row identity establish one exact detached presentation. */
internal class QueryResultPresentation(private val state: QueryStateStore) {
    fun present(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        items: List<QueryResultItemDocument>,
        retainedExecution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
        presentedRetention: QueryResultRetention?,
        presentedRowIds: List<QueryResultRowReference>?,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<PresentedQueryRows, QueryExecutionRejectionDocument> {
        val issuance =
            if (presentedRetention != null) null
            else
                when (
                    val requested = requestedRetention(request, lease, retainedExecution, progress, publicationOwner)
                ) {
                    is Refinement.Refined -> requested.value
                    is Refinement.Rejected -> return presentationRejected()
                }
        val retention =
            presentedRetention
                ?: when (issuance) {
                    null -> QueryResultRetention.NotRequested
                    is QueryResultIssuance.Issued -> QueryResultRetention.Retained(issuance.reference)
                    QueryResultIssuance.Unavailable ->
                        return Refinement.Rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                    QueryResultIssuance.CapacityExceeded -> QueryResultRetention.CapacityExceeded
                }
        val rowIds = presentedRowIds ?: (issuance as? QueryResultIssuance.Issued)?.rowIds
        if (retention is QueryResultRetention.Retained && rowIds == null) return presentationRejected()
        val identified =
            when (val projected = identifyRows(items, rowIds)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return presentationRejected()
            }
        val bounded = BoundedProtocolList.create(identified).refinedForQueryOrNull() ?: return presentationRejected()
        return Refinement.Refined(PresentedQueryRows(bounded, retention))
    }

    private fun presentationRejected(): Refinement.Rejected<QueryExecutionRejectionDocument> =
        Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)

    private fun requestedRetention(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        execution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<QueryResultIssuance?, QueryExecutionRejectionDocument> {
        if (request.retention == QueryRetentionModeDocument.DISCARD) {
            return Refinement.Refined(null)
        }
        val retained =
            execution ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val captured =
            when (val result = QueryRetainedResult.capture(lease, retained)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            }
        val protectedCheckpoint =
            ((progress as? QueryQualifiedProgressDocument.Resumable)?.checkpoint as? QueryCheckpointDocument.Upstream)
                ?.token
        return Refinement.Refined(state.issueResult(request, captured, protectedCheckpoint, publicationOwner))
    }
}
