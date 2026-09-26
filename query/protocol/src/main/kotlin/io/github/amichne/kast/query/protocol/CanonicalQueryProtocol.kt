package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.workspace.contract.*

/** Public query admission and projection around the in-process typed evaluator. */
class CanonicalQueryProtocol(
    private val operations: QueryOperations,
    private val authority: QueryReferenceAuthority,
    private val state: QueryStateStore = QueryStateStore(),
) {
    private val projection = QueryOutcomeProjection(authority, state)

    suspend fun execute(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> =
        when (request) {
            is QueryRunRequest.Run -> executeAdmitted(request, lease, budget, null)
            is QueryRunRequest.Resume ->
                when (val continuation = request.continuation) {
                    is QueryExecutionContinuation.Pipeline ->
                        when (val restored = state.restoreCheckpoint(continuation, lease)) {
                            is QueryCheckpointRestoration.Restored ->
                                executeAdmitted(restored.request, lease, budget, restored.checkpoint)
                            QueryCheckpointRestoration.Unavailable ->
                                rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                            QueryCheckpointRestoration.Mismatch ->
                                rejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
                        }
                    is QueryExecutionContinuation.Output ->
                        rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                }
            is QueryRunRequest.ReadResult -> projection.readRetained(request, lease)
        }

    private suspend fun executeAdmitted(
        request: QueryRunRequest.Run,
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
        return projection.projectExecution(request, lease, operations.run(execution))
    }

    /** A restored checkpoint already contains the plan proven for this exact request and lease. */
    private suspend fun admitPlan(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
    ): Refinement<AdmittedQueryPlan, QueryRunRejection> {
        val retained =
            when (val restored = restoreInputs(request, lease)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        val admissionAuthority = admissionAuthority(request, lease)
        val syntax =
            when (val admission = request.admitSyntax(lease, admissionAuthority, retained)) {
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

    private fun restoreInputs(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
    ): Refinement<Map<QueryFromDocument.Result, QueryRetainedResult>, QueryRunRejection> {
        val retained = linkedMapOf<QueryFromDocument.Result, QueryRetainedResult>()
        for (source in request.resultInputs()) {
            if (source in retained) continue
            when (val restored = state.restoreResult(source.reference, lease)) {
                is QueryResultRestoration.Restored -> {
                    val selected =
                        restored.selectRows(source.rowIds?.values)
                            ?: return rejectedResultInput(QueryExecutionRejectionDocument.RESULT_ROW_UNAVAILABLE)
                    retained[source] = selected
                }
                QueryResultRestoration.Unavailable ->
                    return rejectedResultInput(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
                QueryResultRestoration.StaleBasis ->
                    return rejectedResultInput(QueryExecutionRejectionDocument.RESULT_STALE_BASIS)
            }
        }
        return Refinement.Refined(retained)
    }

    private fun rejectedResultInput(reason: QueryExecutionRejectionDocument): Refinement.Rejected<QueryRunRejection> =
        Refinement.Rejected(QueryRunRejection.ExecutionRejected(reason))

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
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
    ): QueryReferenceAuthority {
        val sourceReferences =
            (request.from as? QueryFromDocument.References)
                ?.values
                ?.values
                ?.filterIsInstance<QueryReferenceDocument.ExactSymbol>()
                ?.map { it.token }
                .orEmpty()
        val concatenatedReferences =
            request.steps.values
                .filterIsInstance<QueryStepDocument.Concat>()
                .mapNotNull { it.input as? QueryFromDocument.References }
                .flatMap { it.values.values.map(QueryReferenceDocument.ExactSymbol::token) }
        val references = sourceReferences + concatenatedReferences
        return if (references.isEmpty()) authority else authority.admitReadReferences(references, lease)
    }

    private fun rejected(reason: QueryExecutionRejectionDocument): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
}

private fun QueryRunRequest.Run.resultInputs(): List<QueryFromDocument.Result> = buildList {
    (from as? QueryFromDocument.Result)?.let(::add)
    steps.values.forEach { step ->
        when (step) {
            is QueryStepDocument.Concat -> (step.input as? QueryFromDocument.Result)?.let(::add)
            is QueryStepDocument.Join -> (step.right as? QueryFromDocument.Result)?.let(::add)
            is QueryStepDocument.Intersect -> add(step.right)
            is QueryStepDocument.Union -> add(step.right)
            is QueryStepDocument.Difference -> add(step.right)
            else -> Unit
        }
    }
}

private fun QueryResultRestoration.Restored.selectRows(
    selectedIds: List<QueryResultRowReference>?
): QueryRetainedResult? {
    if (selectedIds == null) return result
    if (selectedIds.distinct().size != selectedIds.size) return null
    val positions = rowIds.withIndex().associate { (position, rowId) -> rowId to position }
    val indices = selectedIds.map { positions[it] ?: return null }
    return when (val selected = result.selectRows(indices)) {
        is Refinement.Refined -> selected.value
        is Refinement.Rejected -> null
    }
}
