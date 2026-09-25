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
        val plan =
            when (
                val admission =
                    if (checkpoint == null) admitPlan(request, lease) else Refinement.Refined(checkpoint.plan)
            ) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(admission.failure)
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
                        plan = plan,
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

    /** A restored checkpoint already contains the plan proven for this exact request and lease. */
    private suspend fun admitPlan(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
    ): Refinement<AdmittedQueryPlan, QueryRunRejection> {
        val admissionAuthority = admissionAuthority(request, lease)
        val syntax =
            when (val admission = request.admitSyntax(lease, admissionAuthority)) {
                is QuerySyntaxAdmission.Admitted -> admission.syntax
                is QuerySyntaxAdmission.ReferenceRejected ->
                    return Refinement.Rejected(
                        QueryRunRejection.ReferenceRejected(queryPosition(admission.position), admission.reason)
                    )
                is QuerySyntaxAdmission.StepReferenceRejected ->
                    return Refinement.Rejected(
                        QueryRunRejection.StepReferenceRejected(
                            queryPosition(admission.stepPosition),
                            queryPosition(admission.referencePosition),
                            admission.reason,
                        )
                    )
                QuerySyntaxAdmission.RequestRejected ->
                    return Refinement.Rejected(
                        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED)
                    )
            }
        return when (val admitted = QueryPlanCompiler.admit(syntax)) {
            is QueryPlanAdmission.Admitted -> Refinement.Refined(admitted.plan)
            is QueryPlanAdmission.Rejected -> Refinement.Rejected(admitted.failure.protocolRejection())
        }
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
    ): QueryReferenceAuthority {
        val sourceReferences =
            (request.from as? QueryFromDocument.References)
                ?.values
                ?.values
                ?.filterIsInstance<QueryReferenceDocument.ExactSymbol>()
                ?.map { it.token }
                .orEmpty()
        val appendedReferences =
            request.steps.values.filterIsInstance<QueryStepDocument.AppendReferences>().flatMap { it.values.values }
        val references = sourceReferences + appendedReferences
        return if (references.isEmpty()) authority else authority.admitReadReferences(references, lease)
    }

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
            when (val projected = QueryItemProjector(authority).projectItems(request.output, result.items)) {
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
                    QueryKnownMinimum.parse(coverage.knownMinimum.value).refinedForQueryOrNull()
                        ?: return contractRejected(),
                    coverage.limitations.map { QueryLimitationDocument.valueOf(it.name) },
                    progress,
                )
                .refinedForQueryOrNull() ?: return contractRejected()
        return OperationOutcome.Qualified(envelope, qualification)
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
}

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
