package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRetainedRowOrdinal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal data class PresentedQueryRows(
    val items: BoundedProtocolList<QueryResultItemDocument>,
    val retention: QueryResultRetention,
    val selection: QueryPresentedWindowSelection,
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
                    null,
                    QueryPresentedResultIssuance.NotRequested -> QueryResultRetention.NotRequested
                    is QueryPresentedResultIssuance.Issued -> QueryResultRetention.Retained(issuance.original.reference)
                    QueryPresentedResultIssuance.Unavailable ->
                        return Refinement.Rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                    QueryPresentedResultIssuance.CapacityExceeded -> QueryResultRetention.CapacityExceeded
                }
        val rowIds = presentedRowIds ?: (issuance as? QueryPresentedResultIssuance.Issued)?.presentedRowIds
        if (retention is QueryResultRetention.Retained && rowIds == null) return presentationRejected()
        val identified =
            when (val projected = identifyRows(items, rowIds)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return presentationRejected()
            }
        val bounded = BoundedProtocolList.create(identified).refinedForQueryOrNull() ?: return presentationRejected()
        val selection =
            (issuance as? QueryPresentedResultIssuance.Issued)?.selection ?: QueryPresentedWindowSelection.NotRetained
        return Refinement.Refined(PresentedQueryRows(bounded, retention, selection))
    }

    private fun presentationRejected(): Refinement.Rejected<QueryExecutionRejectionDocument> =
        Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)

    private fun requestedRetention(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        execution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<QueryPresentedResultIssuance, QueryExecutionRejectionDocument> {
        if (request.retention == QueryRetentionModeDocument.DISCARD) {
            return Refinement.Refined(QueryPresentedResultIssuance.NotRequested)
        }
        val retained =
            execution ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val captured =
            when (
                val result =
                    if (request.from is QueryFromDocument.Impact)
                        QueryRetainedResult.captureInvestigation(lease, retained)
                    else QueryRetainedResult.capture(lease, retained)
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            }
        val protectedCheckpoint =
            ((progress as? QueryQualifiedProgressDocument.Resumable)?.checkpoint as? QueryCheckpointDocument.Upstream)
                ?.token
        val selection =
            when (val selected = presentedOrdinals(request, retained, captured)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return selected
            }
        return Refinement.Refined(
            when (val issued = state.issueResult(request, captured, protectedCheckpoint, publicationOwner)) {
                QueryResultIssuance.Unavailable -> QueryPresentedResultIssuance.Unavailable
                QueryResultIssuance.CapacityExceeded -> QueryPresentedResultIssuance.CapacityExceeded
                is QueryResultIssuance.Issued -> return QueryPresentedResultIssuance.Issued.admit(issued, selection)
            }
        )
    }

    private fun presentedOrdinals(
        request: QueryRunRequest.Run,
        execution: QueryExecutionResult,
        retained: QueryRetainedResult,
    ): Refinement<List<QueryRetainedRowOrdinal>, QueryExecutionRejectionDocument> {
        if (request.from !is QueryFromDocument.Impact) {
            val ordinals = mutableListOf<QueryRetainedRowOrdinal>()
            for (index in 0 until retained.rowCount) when (
                val admitted = QueryRetainedRowOrdinal.admit(index, retained.rowCount)
            ) {
                is Refinement.Refined -> ordinals += admitted.value
                is Refinement.Rejected -> return presentationRejected()
            }
            return Refinement.Refined(ordinals)
        }
        val result =
            when (execution) {
                is QueryExecutionResult.Complete -> execution.result
                is QueryExecutionResult.Qualified -> execution.result
                is QueryExecutionResult.Rejection -> return presentationRejected()
            }
        val original = retained as? QueryRetainedResult.ValuePaths ?: return presentationRejected()
        val selected = result.rows as? QueryRows.ValuePaths ?: return presentationRejected()
        return when (val ordinals = original.originalOrdinals(selected)) {
            is Refinement.Refined -> Refinement.Refined(ordinals.value)
            is Refinement.Rejected -> presentationRejected()
        }
    }
}
