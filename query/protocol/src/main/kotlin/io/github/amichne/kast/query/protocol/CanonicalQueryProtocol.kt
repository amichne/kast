package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.*

/** Public query admission and projection around the in-process typed evaluator. */
class CanonicalQueryProtocol(
    private val operations: QueryOperations,
    private val authority: QueryReferenceAuthority,
    private val checkpoints: QueryCheckpointStore = QueryCheckpointStore(),
) {
    suspend fun execute(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val continuationToken = request.continuation
        val checkpoint =
            if (continuationToken == null) null
            else {
                when (val restored = checkpoints.restore(continuationToken, request, lease)) {
                    is QueryCheckpointRestoration.Restored -> restored.checkpoint
                    QueryCheckpointRestoration.Unavailable ->
                        return OperationOutcome.Rejected(
                            QueryRunRejection.ExecutionRejected(
                                QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE
                            )
                        )
                    QueryCheckpointRestoration.Mismatch ->
                        return OperationOutcome.Rejected(
                            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
                        )
                }
            }
        return executeAdmitted(request = request, lease = lease, budget = budget, checkpoint = checkpoint)
    }

    private suspend fun executeAdmitted(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
        checkpoint: QueryCheckpoint?,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val admissionAuthority = admissionAuthority(request, lease, checkpoint)
        val syntax =
            when (val admission = request.admitSyntax(lease, admissionAuthority)) {
                is QuerySyntaxAdmission.Admitted -> admission.syntax
                is QuerySyntaxAdmission.ReferenceRejected ->
                    return OperationOutcome.Rejected(
                        QueryRunRejection.ReferenceRejected(queryPosition(admission.position), admission.reason)
                    )
                QuerySyntaxAdmission.RequestRejected ->
                    return OperationOutcome.Rejected(
                        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED)
                    )
            }
        val plan =
            when (val admitted = QueryPlanCompiler.admit(syntax)) {
                is QueryPlanAdmission.Admitted -> admitted.plan
                is QueryPlanAdmission.Rejected -> return OperationOutcome.Rejected(admitted.failure.protocolRejection())
            }
        val resources =
            when (val remaining = remainingResources(budget)) {
                is Refinement.Refined -> remaining.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(remaining.failure)
            }
        val execution =
            when (
                val admitted =
                    QueryExecutionRequest.create(
                        plan = checkpoint?.plan ?: plan,
                        lease = lease,
                        budget = if (resources == budget.resources) budget else budget.copy(resources = resources),
                        checkpoint = checkpoint,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return OperationOutcome.Rejected(
                        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED)
                    )
            }
        return projectExecution(request, lease, operations.run(execution))
    }

    private fun remainingResources(
        budget: QueryBudget
    ): Refinement<io.github.amichne.kast.kernel.ResourceBudget, QueryRunRejection> =
        when (val remaining = authority.remainingReadBudget(budget.resources)) {
            is Refinement.Refined -> remaining
            is Refinement.Rejected ->
                Refinement.Rejected(
                    QueryRunRejection.ReferenceRejected(
                        queryPosition(0),
                        when (remaining.failure) {
                            ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED ->
                                QueryReferenceRejectionReason.REVALIDATION_WORK_LIMIT_REACHED
                            ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED ->
                                QueryReferenceRejectionReason.REVALIDATION_TIME_LIMIT_REACHED
                        },
                    )
                )
        }

    private suspend fun admissionAuthority(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        checkpoint: QueryCheckpoint?,
    ): QueryReferenceAuthority =
        if (checkpoint == null && request.from is QueryFromDocument.References)
            authority.admitReadReferences(
                (request.from as QueryFromDocument.References)
                    .values
                    .values
                    .filterIsInstance<QueryReferenceDocument.ExactSymbol>()
                    .map { it.token },
                lease,
            )
        else authority

    private fun projectExecution(
        request: QueryRunRequest,
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
                )
            is QueryExecutionResult.Qualified ->
                project(
                    request = request,
                    lease = lease,
                    result = result.result,
                    coverage = result.coverage,
                    continuationState = result.continuation,
                )
            is QueryExecutionResult.Rejected ->
                OperationOutcome.Rejected(
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.valueOf(result.reason.name))
                )
        }

    private fun project(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        result: QueryResult,
        coverage: QueryCoverage.Qualified?,
        continuationState: QueryContinuationState?,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val items =
            when (val projected = projectItems(request.output, result.items)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return contractRejected()
            }
        val failures =
            when (val projected = result.failures.mapProjected(::projectFailure)) {
                is QueryProjection.Projected -> projected.values
                QueryProjection.Rejected -> return contractRejected()
            }
        val boundedItems = BoundedProtocolList.create(items).refinedOrNull() ?: return contractRejected()
        val boundedFailures = BoundedProtocolList.create(failures).refinedOrNull() ?: return contractRejected()
        val envelope =
            EvidenceEnvelope(
                CanonicalOperation.QUERY_RUN.id,
                lease.evidenceBasis(),
                QueryRunResult(
                    items = boundedItems,
                    failures = boundedFailures,
                    referenceAcquisitions = authority.readAcquisitions(),
                ),
            )
        if (coverage == null) {
            if (continuationState != null) return contractRejected()
            return OperationOutcome.Complete(envelope)
        }
        if (continuationState == null) return contractRejected()
        val progress = projectQueryProgress(request, continuationState, items.size, checkpoints)
        val qualification =
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(coverage.knownMinimum.value).refinedOrNull() ?: return contractRejected(),
                    coverage.limitations.map { QueryLimitationDocument.valueOf(it.name) },
                    progress,
                )
                .refinedOrNull() ?: return contractRejected()
        return OperationOutcome.Qualified(envelope, qualification)
    }

    private fun projectItems(
        output: QueryOutputDocument,
        items: QueryResultSet,
    ): QueryProjection<QueryResultItemDocument> =
        when {
            output is QueryOutputDocument.Candidates && items is QueryResultSet.Candidates ->
                items.values.mapProjected { candidate ->
                    val token =
                        when (val issued = authority.issueDeclarationCandidate(candidate.selection)) {
                            is CandidateSelectorTokenIssuance.Issued -> issued.selector
                            is CandidateSelectorTokenIssuance.Rejected -> return@mapProjected null
                        }
                    val document =
                        candidate.selection.candidate.protocolDocument(token) as? SymbolDiscoveryDocument.Declaration
                            ?: return@mapProjected null
                    QueryResultItemDocument.Candidate(
                        QueryReferenceDocument.DeclarationCandidate(token),
                        document.kind,
                        document.name.takeIf { QueryCandidateFieldDocument.NAME in output.fields.values },
                        QueryCandidateLocationDocument(document.file, document.offset).takeIf {
                            QueryCandidateFieldDocument.LOCATION in output.fields.values
                        },
                    )
                }
            output is QueryOutputDocument.Symbols && items is QueryResultSet.Symbols ->
                items.values.mapProjected { symbol ->
                    val token =
                        when (val issued = authority.issueExact(symbol.selector)) {
                            is ExactSelectorIssuance.Issued -> issued.selector
                            is ExactSelectorIssuance.Rejected -> return@mapProjected null
                        }
                    val document = symbol.description.protocolDocument(token) ?: return@mapProjected null
                    val connections = symbol.connections.mapProjected { it.protocolDocument(authority) }
                    val boundedConnections =
                        when (connections) {
                            is QueryProjection.Projected ->
                                BoundedProtocolList.create(connections.values).refinedOrNull()
                                    ?: return@mapProjected null
                            QueryProjection.Rejected -> return@mapProjected null
                        }
                    QueryResultItemDocument.ExactSymbol(
                        ref = QueryReferenceDocument.ExactSymbol(token),
                        kind = document.kind,
                        name = document.name.takeIf { QuerySymbolFieldDocument.NAME in output.fields.values },
                        location =
                            QueryExactLocationDocument(document.file, document.range).takeIf {
                                QuerySymbolFieldDocument.LOCATION in output.fields.values
                            },
                        signature =
                            document.compilerEvidence.signature.takeIf {
                                QuerySymbolFieldDocument.SIGNATURE in output.fields.values
                            },
                        connections = boundedConnections,
                        symbolId =
                            io.github.amichne.kast.protocol.contract.SymbolIdDocument.parse(
                                    io.github.amichne.kast.symbol.contract.CanonicalSymbolId.from(symbol.selector).value
                                )
                                .refinedOrNull() ?: return@mapProjected null,
                    )
                }
            else -> QueryProjection.Rejected
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
}

private sealed interface QueryProjection<out Value> {
    data class Projected<Value>(val values: List<Value>) : QueryProjection<Value>

    data object Rejected : QueryProjection<Nothing>
}

private inline fun <Input, Output : Any> Iterable<Input>.mapProjected(
    transform: (Input) -> Output?
): QueryProjection<Output> {
    val values = mutableListOf<Output>()
    for (input in this) values += transform(input) ?: return QueryProjection.Rejected
    return QueryProjection.Projected(values)
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
