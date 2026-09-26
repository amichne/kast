package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector
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
        val rows = restored.result.symbols
        if (
            QuerySymbolFieldDocument.SOURCE in request.output.fields.values &&
                rows.any { it.source == QuerySymbolSource.Pending }
        ) {
            return rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
        }
        val start = request.cursor.value
        if (start > rows.size) return rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
        val end = minOf(start + RESULT_PAGE_SIZE, rows.size)
        val next =
            if (end == rows.size) null
            else QueryResultCursor.parse(end).refinedForQueryOrNull() ?: return contractRejected()
        val coverage = restored.result.coverage as? QueryCoverage.Qualified
        return project(
            request = restored.request,
            lease = lease,
            result = QueryResult(rows.subList(start, end), restored.result.failures),
            coverage = coverage,
            continuationState = restored.result.producerProgress,
            output = request.output,
            progressItemCount = rows.size,
            presentedRetention = QueryResultRetention.Retained(request.result),
            protectedResult = request.result,
            nextCursor = next,
        )
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
        protectedResult: QueryResultReference? = null,
        nextCursor: QueryResultCursor? = null,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val items =
            when (val projected = QueryItemProjector(authority).projectItems(output, result.items)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return contractRejected()
            }
        val failures =
            when (val projected = result.failures.mapProjected(::projectFailure)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return contractRejected()
            }
        val boundedItems = BoundedProtocolList.create(items).refinedForQueryOrNull() ?: return contractRejected()
        val boundedFailures = BoundedProtocolList.create(failures).refinedForQueryOrNull() ?: return contractRejected()
        val qualification =
            when (
                val projected =
                    projectQualification(
                        request,
                        coverage,
                        continuationState,
                        progressItemCount ?: items.size,
                        protectedResult,
                    )
            ) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return contractRejected()
            }
        val retention =
            presentedRetention
                ?: requestedRetention(request, lease, retainedExecution, qualification?.progress)
                    .refinedForQueryOrNull()
                ?: return contractRejected()
        val envelope =
            EvidenceEnvelope(
                CanonicalOperation.QUERY_RUN.id,
                lease.evidenceBasis(),
                QueryRunResult(
                    items = boundedItems,
                    failures = boundedFailures,
                    retention = retention,
                    nextCursor = nextCursor,
                    referenceAcquisitions = authority.readAcquisitions(),
                ),
            )
        return if (qualification == null) OperationOutcome.Complete(envelope)
        else OperationOutcome.Qualified(envelope, qualification)
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
    ): Refinement<QueryResultRetention, QueryExecutionRejectionDocument> {
        if (request.retention == QueryRetentionModeDocument.DISCARD) {
            return Refinement.Refined(QueryResultRetention.NotRequested)
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
        return Refinement.Refined(
            when (val issued = state.issueResult(request, captured, protectedCheckpoint)) {
                is QueryResultIssuance.Issued -> QueryResultRetention.Retained(issued.reference)
                QueryResultIssuance.CapacityExceeded -> QueryResultRetention.CapacityExceeded
            }
        )
    }

    private fun projectFailure(failure: QueryItemFailure): QueryItemFailureDocument? =
        when (failure) {
            is QueryItemFailure.Refinement -> {
                val token =
                    (authority.issueDeclarationCandidate(failure.candidate) as? CandidateSelectorTokenIssuance.Issued)
                        ?.selector ?: return null
                QueryItemFailureDocument.Refinement(
                    QueryReferenceDocument.DeclarationCandidate(token),
                    QueryExactFailureDocument.valueOf(failure.reason.name),
                )
            }
            is QueryItemFailure.ExactReference -> exactFailure(failure.selector, failure.reason)
            is QueryItemFailure.Visibility ->
                QueryItemFailureDocument.Predicate(
                    exactReference(failure.selector) ?: return null,
                    QueryPredicateFailureDocument.valueOf(failure.reason.name),
                )
            is QueryItemFailure.PredicateUnproven ->
                QueryItemFailureDocument.Predicate(
                    exactReference(failure.selector) ?: return null,
                    QueryPredicateFailureDocument.PREDICATE_UNPROVEN,
                )
            is QueryItemFailure.Source ->
                QueryItemFailureDocument.Source(
                    exactReference(failure.selector) ?: return null,
                    QuerySourceFailureDocument.valueOf(
                        when (val cause = failure.reason) {
                            is QuerySourceFailure.Rejected -> cause.reason.name
                            is QuerySourceFailure.Withheld -> cause.reason.name
                        }
                    ),
                )
            is QueryItemFailure.Relation ->
                QueryItemFailureDocument.Relation(
                    exactReference(failure.selector) ?: return null,
                    failure.meaning.protocolDocument(),
                    QueryRelationFailureDocument.valueOf(failure.reason.name),
                )
        }

    private fun exactFailure(
        selector: SymbolSelector,
        reason: SymbolExactRejection,
    ): QueryItemFailureDocument.ExactReference? =
        QueryItemFailureDocument.ExactReference(
            exactReference(selector) ?: return null,
            QueryExactFailureDocument.valueOf(reason.name),
        )

    private fun exactReference(selector: SymbolSelector): QueryReferenceDocument.ExactSymbol? =
        when (val issued = authority.issueExact(selector)) {
            is ExactSelectorIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is ExactSelectorIssuance.Rejected -> null
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

internal fun <Value, Failure> Refinement<Value, Failure>.refinedForQueryOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
