package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Projects semantic rows and retained presentations through one canonical query outcome. */
internal class QueryOutcomeProjection(
    private val authority: QueryReferenceAuthority,
    private val state: QueryStateStore,
    private val retentionObservation: QueryResultRetentionObservation = QueryResultRetentionObservation.None,
) {
    fun projectExecution(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        result: QueryExecutionResult,
        publicationOwner: QueryExecutionClaim? = null,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> =
        when (result) {
            is QueryExecutionResult.ImpactRejected ->
                OperationOutcome.Rejected(QueryRunRejection.ImpactExecutionRejected(result.failure.executionDocument()))
            is QueryExecutionResult.Complete ->
                project(
                    request = request,
                    lease = lease,
                    result = result.result,
                    coverage = null,
                    continuationState = null,
                    retainedExecution = result,
                    publicationOwner = publicationOwner,
                    presentationOrigin = result.coverage.resultCount.value,
                )
            is QueryExecutionResult.Qualified ->
                project(
                    request = request,
                    lease = lease,
                    result = result.result,
                    coverage = result.coverage,
                    continuationState = result.continuation,
                    retainedExecution = result,
                    publicationOwner = publicationOwner,
                    presentationOrigin = result.coverage.knownMinimum.value,
                )
            is QueryExecutionResult.Rejected ->
                OperationOutcome.Rejected(
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.valueOf(result.reason.name))
                )
        }

    fun readRetained(
        request: QueryRunRequest.ReadResult,
        lease: SemanticReadAuthority,
        publicationOwner: QueryExecutionClaim,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val restored =
            when (val value = state.restoreResult(request.result, lease, publicationOwner)) {
                is QueryResultRestoration.Restored -> value
                QueryResultRestoration.Unavailable ->
                    return rejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
                QueryResultRestoration.StaleBasis -> return rejected(QueryExecutionRejectionDocument.RESULT_STALE_BASIS)
            }
        val presentation =
            when (val admitted = RetainedQueryPresentation.create(restored, request)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(admitted.failure)
            }
        return project(
            request = restored.request,
            lease = lease,
            result = presentation.result,
            coverage = presentation.coverage,
            continuationState = presentation.producerProgress,
            output = request.output,
            presentedRetention = QueryResultRetention.Retained(request.result),
            presentedRowIds = presentation.rowIds,
            protectedResult = request.result,
            presentationWindow = presentation.window,
            progressOrigin = QueryProgressOrigin.RETAINED_RESULT,
            presentationOrigin =
                when (val original = restored.result.coverage) {
                    is QueryCoverage.Complete -> original.resultCount.value
                    is QueryCoverage.Qualified -> original.knownMinimum.value
                },
        )
    }

    private fun project(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        result: QueryResult,
        coverage: QueryCoverage.Qualified?,
        continuationState: QueryContinuationState?,
        output: QueryOutputDocument = request.output,
        retainedExecution: QueryExecutionResult? = null,
        presentedRetention: QueryResultRetention? = null,
        presentedRowIds: List<QueryResultRowReference>? = null,
        protectedResult: QueryResultReference? = null,
        presentationWindow: QueryRetainedPresentationWindow? = null,
        progressOrigin: QueryProgressOrigin = QueryProgressOrigin.EXECUTION,
        presentationOrigin: Int? = null,
        publicationOwner: QueryExecutionClaim? = null,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val evidence =
            when (val projected = QueryProjectedEvidence.from(result, output, authority, presentedRowIds)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(projected.failure)
            }
        val qualification =
            when (
                val projected =
                    projectQualification(
                        request,
                        coverage,
                        continuationState,
                        protectedResult,
                        progressOrigin,
                        publicationOwner,
                    )
            ) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return contractRejected()
            }
        val presented =
            when (
                val projected =
                    QueryResultPresentation(state, retentionObservation)
                        .present(
                            request,
                            lease,
                            evidence.items,
                            retainedExecution,
                            qualification?.progress,
                            presentedRetention,
                            presentedRowIds,
                            publicationOwner,
                        )
            ) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(projected.failure)
            }
        return finishProjection(
            lease,
            io.github.amichne.kast.protocol.contract.QueryQuestionDocument.from(request),
            evidence,
            presented,
            qualification,
            presentationWindow,
            presentationOrigin,
        )
    }

    private fun finishProjection(
        lease: SemanticReadAuthority,
        question: io.github.amichne.kast.protocol.contract.QueryQuestionDocument,
        evidence: QueryProjectedEvidence,
        presented: PresentedQueryRows,
        qualification: QueryRunQualification?,
        window: QueryRetainedPresentationWindow?,
        origin: Int?,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val minimum = origin?.let {
            QueryKnownMinimum.parse(it).refinedForQueryOrNull() ?: return contractRejected()
        }
        val fittedWindow =
            when (
                val admitted =
                    presented.retention.presentationWindow(
                        window
                            ?: when (val selection = presented.selection) {
                                is QueryPresentedWindowSelection.Contiguous -> selection.window
                                is QueryPresentedWindowSelection.NonContiguous -> selection.window
                                QueryPresentedWindowSelection.NotRetained -> null
                            },
                        presented.items.values.size,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return contractRejected()
            }
        val envelope =
            authority.resultEnvelope(
                lease,
                question,
                presented,
                evidence,
                fittedWindow,
                minimum,
            )
        return if (qualification == null) OperationOutcome.Complete(envelope)
        else OperationOutcome.Qualified(envelope, qualification)
    }

    private fun projectQualification(
        request: QueryRunRequest.Run,
        coverage: QueryCoverage.Qualified?,
        continuationState: QueryContinuationState?,
        protectedResult: QueryResultReference?,
        origin: QueryProgressOrigin,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<QueryRunQualification?, QueryExecutionRejectionDocument> {
        if (coverage == null && continuationState == null) return Refinement.Refined(null)
        val provenCoverage =
            coverage ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val state =
            continuationState ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val minimum =
            QueryKnownMinimum.parse(provenCoverage.knownMinimum.value).refinedForQueryOrNull()
                ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val progress = projectQueryProgress(request, state, this.state, protectedResult, origin, publicationOwner)
        return when (
            val result =
                QueryRunQualification.create(
                    minimum,
                    provenCoverage.limitations.map { QueryLimitationDocument.valueOf(it.name) },
                    progress,
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(result.value)
            is Refinement.Rejected -> Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        }
    }

    private fun contractRejected(): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        )

    private fun rejected(reason: QueryExecutionRejectionDocument): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
}
