package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Projects semantic rows and retained presentations through one canonical query outcome. */
internal class QueryOutcomeProjection(
    private val authority: QueryReferenceAuthority,
    private val state: QueryStateStore,
) {
    fun projectExecution(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        result: QueryExecutionResult,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> =
        when (result) {
            is QueryExecutionResult.Complete ->
                project(
                    request = request,
                    lease = lease,
                    result = result.result,
                    coverage = null,
                    continuationState = null,
                    retainedExecution = result,
                )
            is QueryExecutionResult.Qualified ->
                project(
                    request = request,
                    lease = lease,
                    result = result.result,
                    coverage = result.coverage,
                    continuationState = result.continuation,
                    retainedExecution = result,
                )
            is QueryExecutionResult.Rejected ->
                OperationOutcome.Rejected(
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.valueOf(result.reason.name))
                )
        }

    fun readRetained(
        request: QueryRunRequest.ReadResult,
        lease: SemanticReadAuthority,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val restored =
            when (val value = state.restoreResult(request.result, lease)) {
                is QueryResultRestoration.Restored -> value
                QueryResultRestoration.Unavailable ->
                    return rejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
                QueryResultRestoration.StaleBasis -> return rejected(QueryExecutionRejectionDocument.RESULT_STALE_BASIS)
            }
        val rowCount = restored.result.rowCount
        val start = request.cursor.value
        if (start > rowCount) return rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
        val end = minOf(start + RESULT_PAGE_SIZE, rowCount)
        val rows =
            retainedRows(restored.result, request.output, start, end)
                ?: return rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
        val next =
            if (end == rowCount) null
            else QueryResultCursor.parse(end).refinedForQueryOrNull() ?: return contractRejected()
        val coverage = restored.result.coverage as? QueryCoverage.Qualified
        return project(
            request = restored.request,
            lease = lease,
            result =
                QueryResult(
                    rows,
                    restored.result.failures,
                    restored.result.omissions,
                    restored.result.walkObservations,
                ),
            coverage = coverage,
            continuationState = restored.result.producerProgress,
            output = request.output,
            progressItemCount = rowCount,
            presentedRetention = QueryResultRetention.Retained(request.result),
            presentedRowIds = restored.rowIds.subList(start, end),
            protectedResult = request.result,
            nextCursor = next,
        )
    }

    private fun retainedRows(
        retained: QueryRetainedResult,
        output: QueryOutputDocument,
        start: Int,
        end: Int,
    ): QueryRows? =
        when (retained) {
            is QueryRetainedResult.Symbols -> {
                val selected = output as? QueryOutputDocument.Symbols ?: return null
                if (
                    QuerySymbolFieldDocument.SOURCE in selected.fields.values &&
                        retained.symbols.any { it.source == QuerySymbolSource.Pending }
                )
                    return null
                QueryRows.Symbols.of(retained.symbols.subList(start, end))
            }
            is QueryRetainedResult.Bindings ->
                if (output == QueryOutputDocument.BindingRows)
                    QueryRows.Bindings.of(retained.bindingRows.subList(start, end))
                else null
        }

    private fun project(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        result: QueryResult,
        coverage: QueryCoverage.Qualified?,
        continuationState: QueryContinuationState?,
        output: QueryOutputDocument = request.output,
        progressItemCount: Int? = null,
        retainedExecution: QueryExecutionResult? = null,
        presentedRetention: QueryResultRetention? = null,
        presentedRowIds: List<QueryResultRowReference>? = null,
        protectedResult: QueryResultReference? = null,
        nextCursor: QueryResultCursor? = null,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val items =
            when (val projected = QueryItemProjector(authority).projectItems(output, result.rows)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return contractRejected()
            }
        val boundedFailures =
            result.failures.mapProjected { it.projectIssue(authority) }.boundedProjectedOrNull()
                ?: return contractRejected()
        val boundedOmissions =
            result.omissions.mapProjected { it.projectIssue(authority) }.boundedProjectedOrNull()
                ?: return contractRejected()
        val boundedWalkObservations =
            result.walkObservations.mapProjected { it.projectWalkObservation(authority) }.boundedProjectedOrNull()
                ?: return contractRejected()
        val projectedQualification =
            projectQualification(request, coverage, continuationState, progressItemCount ?: items.size, protectedResult)
        val qualification =
            when (projectedQualification) {
                is Refinement.Refined -> projectedQualification.value
                is Refinement.Rejected -> return contractRejected()
            }
        val projectedRows =
            presentRows(
                request,
                lease,
                items,
                retainedExecution,
                qualification?.progress,
                presentedRetention,
                presentedRowIds,
            )
        val presented =
            when (projectedRows) {
                is Refinement.Refined -> projectedRows.value
                is Refinement.Rejected -> return contractRejected()
            }
        val envelope =
            resultEnvelope(lease, presented, boundedFailures, boundedOmissions, boundedWalkObservations, nextCursor)
        return if (qualification == null) OperationOutcome.Complete(envelope)
        else OperationOutcome.Qualified(envelope, qualification)
    }

    private fun resultEnvelope(
        lease: SemanticReadAuthority,
        presented: PresentedRows,
        failures: BoundedProtocolList<QueryItemFailureDocument>,
        omissions: BoundedProtocolList<QueryRelationOmissionDocument>,
        walkObservations: BoundedProtocolList<QueryWalkObservationDocument>,
        nextCursor: QueryResultCursor?,
    ): EvidenceEnvelope<QueryRunResult> =
        EvidenceEnvelope(
            CanonicalOperation.QUERY_RUN.id,
            lease.evidenceBasis(),
            QueryRunResult(
                items = presented.items,
                failures = failures,
                omissions = omissions,
                walkObservations = walkObservations,
                retention = presented.retention,
                nextCursor = nextCursor,
                referenceAcquisitions = authority.readAcquisitions(),
            ),
        )

    private data class PresentedRows(
        val items: BoundedProtocolList<QueryResultItemDocument>,
        val retention: QueryResultRetention,
    )

    private fun presentRows(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        items: List<QueryResultItemDocument>,
        retainedExecution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
        presentedRetention: QueryResultRetention?,
        presentedRowIds: List<QueryResultRowReference>?,
    ): Refinement<PresentedRows, QueryExecutionRejectionDocument> {
        val issuance =
            if (presentedRetention != null) null
            else
                when (val requested = requestedRetention(request, lease, retainedExecution, progress)) {
                    is Refinement.Refined -> requested.value
                    is Refinement.Rejected -> return presentationRejected()
                }
        val retention =
            presentedRetention
                ?: when (issuance) {
                    null -> QueryResultRetention.NotRequested
                    is QueryResultIssuance.Issued -> QueryResultRetention.Retained(issuance.reference)
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
        return Refinement.Refined(PresentedRows(bounded, retention))
    }

    private fun presentationRejected(): Refinement.Rejected<QueryExecutionRejectionDocument> =
        Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)

    private fun identifyRows(
        items: List<QueryResultItemDocument>,
        rowIds: List<QueryResultRowReference>?,
    ): QueryProjection<QueryResultItemDocument> {
        if (rowIds == null) return QueryProjection.Projected(items)
        if (rowIds.size != items.size) return QueryProjection.Rejected
        return QueryProjection.Projected(
            items.mapIndexed { index, item ->
                when (item) {
                    is QueryResultItemDocument.ExactSymbol -> item.copy(rowId = rowIds[index])
                    is QueryResultItemDocument.Occurrence -> item.copy(rowId = rowIds[index])
                    is QueryResultItemDocument.TraversalRecord -> item.copy(rowId = rowIds[index])
                    is QueryResultItemDocument.BindingRow -> item.withRowId(rowIds[index])
                }
            }
        )
    }

    private fun projectQualification(
        request: QueryRunRequest.Run,
        coverage: QueryCoverage.Qualified?,
        continuationState: QueryContinuationState?,
        itemCount: Int,
        protectedResult: QueryResultReference?,
    ): Refinement<QueryRunQualification?, QueryExecutionRejectionDocument> {
        if (coverage == null && continuationState == null) return Refinement.Refined(null)
        val provenCoverage =
            coverage ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val state =
            continuationState ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val minimum =
            QueryKnownMinimum.parse(provenCoverage.knownMinimum.value).refinedForQueryOrNull()
                ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        val progress = projectQueryProgress(request, state, itemCount, this.state, protectedResult)
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

    private fun requestedRetention(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        execution: QueryExecutionResult?,
        progress: QueryQualifiedProgressDocument?,
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
        return Refinement.Refined(state.issueResult(request, captured, protectedCheckpoint))
    }

    private fun contractRejected(): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        )

    private fun rejected(reason: QueryExecutionRejectionDocument): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
}

private const val RESULT_PAGE_SIZE = 100

internal sealed interface QueryProjection<out Value> {
    data class Projected<Value>(val values: List<Value>) : QueryProjection<Value>

    data object Rejected : QueryProjection<Nothing>
}

internal inline fun <Input, Output : Any> Iterable<Input>.mapProjected(
    transform: (Input) -> Output?
): QueryProjection<Output> {
    val values = mutableListOf<Output>()
    for (input in this) values += transform(input) ?: return QueryProjection.Rejected
    return QueryProjection.Projected(values)
}

internal fun <Value> QueryProjection<Value>.boundedProjectedOrNull(): BoundedProtocolList<Value>? =
    when (this) {
        is QueryProjection.Projected -> BoundedProtocolList.create(values).refinedForQueryOrNull()
        QueryProjection.Rejected -> null
    }

internal fun <Value, Failure> Refinement<Value, Failure>.refinedForQueryOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
