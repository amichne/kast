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
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal data class PresentedQueryRows(
    val items: BoundedProtocolList<QueryResultItemDocument>,
    val retention: QueryResultRetention,
    val selection: QueryPresentedWindowSelection,
)

/** Retention issuance and row identity establish one exact detached presentation. */
internal class QueryResultPresentation(
    private val state: QueryStateStore,
    private val observation: QueryResultRetentionObservation,
) {
    fun present(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        items: List<QueryResultItemDocument>,
        retainedExecution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
        presentedRetention: QueryResultRetention?,
        presentedRowIds: List<QueryResultRowReference>?,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<PresentedQueryRows, QueryRunRejection> {
        val issuance =
            if (presentedRetention != null) null
            else
                when (
                    val requested = requestedRetention(request, lease, retainedExecution, progress, publicationOwner)
                ) {
                    is Refinement.Refined -> requested.value
                    is Refinement.Rejected -> return requested
                }
        val retention =
            presentedRetention
                ?: when (issuance) {
                    null,
                    QueryPresentedResultIssuance.NotRequested -> QueryResultRetention.NotRequested
                    is QueryPresentedResultIssuance.Issued -> QueryResultRetention.Retained(issuance.original.reference)
                    QueryPresentedResultIssuance.Unavailable ->
                        return executionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
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

    private fun presentationRejected(): Refinement.Rejected<QueryRunRejection> =
        executionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)

    private fun requestedRetention(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        execution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<QueryPresentedResultIssuance, QueryRunRejection> {
        if (request.retention == QueryRetentionModeDocument.DISCARD) {
            return Refinement.Refined(QueryPresentedResultIssuance.NotRequested)
        }
        val retained = execution ?: return presentationRejected()
        val source =
            when (val admitted = QueryResultRetentionSource.admit(request.from, retained)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return captureRejected(request, admitted.failure)
            }
        observation.observe(QueryResultRetentionEvidence.CaptureStarted(source.scope))
        val captured =
            when (val result = source.capture(lease)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return captureRejected(request, result.failure)
            }
        observation.observe(QueryResultRetentionEvidence.Captured(source.scope))
        val protectedCheckpoint =
            ((progress as? QueryQualifiedProgressDocument.Resumable)?.checkpoint as? QueryCheckpointDocument.Upstream)
                ?.token
        val selection =
            when (val selected = source.ordinals(captured)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return captureRejected(request, selected.failure)
            }
        val issued = state.issueResult(request, captured, protectedCheckpoint, publicationOwner)
        observation.observe(
            QueryResultRetentionEvidence.Issuance(
                when (issued) {
                    QueryResultIssuance.Unavailable -> QueryResultRetentionIssue.UNAVAILABLE
                    QueryResultIssuance.CapacityExceeded -> QueryResultRetentionIssue.CAPACITY_EXCEEDED
                    is QueryResultIssuance.Issued -> QueryResultRetentionIssue.ISSUED
                }
            )
        )
        return when (issued) {
            QueryResultIssuance.Unavailable -> Refinement.Refined(QueryPresentedResultIssuance.Unavailable)
            QueryResultIssuance.CapacityExceeded -> Refinement.Refined(QueryPresentedResultIssuance.CapacityExceeded)
            is QueryResultIssuance.Issued ->
                when (val admitted = QueryPresentedResultIssuance.Issued.admit(issued, selection)) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected -> executionRejected(admitted.failure)
                }
        }
    }

    private fun captureRejected(
        request: QueryRunRequest.Run,
        failure: QueryRetainedResultFailure,
    ): Refinement.Rejected<QueryRunRejection> {
        observation.observe(QueryResultRetentionEvidence.CaptureRejected(failure))
        return if (request.from is QueryFromDocument.Impact)
            Refinement.Rejected(
                QueryRunRejection.ImpactExecutionRejected(
                    QueryImpactExecutionFailure.Selection(failure).executionDocument()
                )
            )
        else presentationRejected()
    }

    private fun executionRejected(reason: QueryExecutionRejectionDocument): Refinement.Rejected<QueryRunRejection> =
        Refinement.Rejected(QueryRunRejection.ExecutionRejected(reason))
}
