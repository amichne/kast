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
    private val publication: QueryExecutionPublication = QueryExecutionPublication.Immediate,
    private val producerSeeds: io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort =
        UnavailableValueProducerSeeds,
    private val retentionObservation: QueryResultRetentionObservation = QueryResultRetentionObservation.None,
) {
    private val projection = QueryOutcomeProjection(authority, state, retentionObservation)

    suspend fun execute(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> =
        when (request) {
            is QueryRunRequest.Run ->
                when (val acquired = state.acquireInitial(lease, request.resultInputs().map { it.reference }.toSet())) {
                    is QueryInitialAcquisition.Acquired -> executeOwned(request, null, acquired.claim, lease, budget)
                    QueryInitialAcquisition.Unavailable ->
                        rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                    QueryInitialAcquisition.CapacityExceeded ->
                        rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
                }
            is QueryRunRequest.Resume ->
                when (val continuation = request.continuation) {
                    is QueryExecutionContinuation.Pipeline -> executeCheckpoint(continuation, lease, budget)
                    is QueryExecutionContinuation.Output -> executeOutput(continuation, lease, budget)
                }
            is QueryRunRequest.ReadResult ->
                when (val acquired = state.acquireInitial(lease, setOf(request.result))) {
                    is QueryInitialAcquisition.Acquired ->
                        executePage(acquired.claim) { projection.readRetained(request, lease, acquired.claim) }
                    QueryInitialAcquisition.Unavailable ->
                        rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                    QueryInitialAcquisition.CapacityExceeded ->
                        rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
                }
        }

    private suspend fun executeCheckpoint(
        token: QueryExecutionContinuation.Pipeline,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> =
        when (val acquired = state.acquireCheckpoint(token, lease, budget.returnedBytes.value)) {
            is QueryCheckpointAcquisition.Published -> executePage(acquired.claim) { acquired.page }
            QueryCheckpointAcquisition.InUse -> rejected(QueryExecutionRejectionDocument.CONTINUATION_IN_USE)
            QueryCheckpointAcquisition.Unavailable -> rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            QueryCheckpointAcquisition.Mismatch -> rejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
            QueryCheckpointAcquisition.CapacityExceeded ->
                rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
            is QueryCheckpointAcquisition.Acquired ->
                executeOwned(acquired.request, acquired.checkpoint, acquired.claim, lease, budget)
        }

    private suspend fun executeOutput(
        token: QueryExecutionContinuation.Output,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): QueryPublishedPage =
        when (val acquired = state.acquireOutput(token, lease, budget.returnedBytes.value)) {
            is QueryOutputAcquisition.Acquired -> executePage(acquired.claim) { acquired.page }
            is QueryOutputAcquisition.Published -> executePage(acquired.claim) { acquired.page }
            QueryOutputAcquisition.InUse -> rejected(QueryExecutionRejectionDocument.CONTINUATION_IN_USE)
            QueryOutputAcquisition.Unavailable -> rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            QueryOutputAcquisition.Mismatch -> rejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
            QueryOutputAcquisition.CapacityExceeded ->
                rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
        }

    private suspend fun executeOwned(
        request: QueryRunRequest.Run,
        checkpoint: QueryCheckpoint?,
        claim: QueryExecutionClaim,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): QueryPublishedPage {
        return executePage(claim) { executeAdmitted(request, lease, budget, checkpoint, claim) }
    }

    private suspend fun executePage(
        claim: QueryExecutionClaim,
        compute: suspend () -> QueryPublishedPage,
    ): QueryPublishedPage {
        var prepared = false
        return try {
            val page = compute()
            if (page is OperationOutcome.Rejected) page
            else
                when (val published = publication.prepare(state, claim, page)) {
                    QueryExecutionPublicationResult.COMMITTED -> page
                    QueryExecutionPublicationResult.PREPARED -> {
                        prepared = true
                        page
                    }
                    is QueryExecutionPublicationResult.Rejected -> rejected(published.failure.rejection())
                }
        } finally {
            if (!prepared) state.releasePublication(claim)
        }
    }

    private suspend fun executeAdmitted(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
        checkpoint: QueryCheckpoint?,
        publicationOwner: QueryExecutionClaim? = null,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val acquired =
            when (
                val admission =
                    if (checkpoint == null) admitPlan(request, lease, publicationOwner, budget)
                    else Refinement.Refined(QueryPlanAcquisition(checkpoint.plan, 0L))
            ) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(admission.failure)
            }
        val resources =
            when (val remaining = remainingResources(budget, acquired.examinedWork)) {
                is Refinement.Refined -> remaining.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(remaining.failure)
            }
        val execution =
            when (
                val admitted =
                    QueryExecutionRequest.create(
                        plan = acquired.plan,
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
        return projection.projectExecution(request, lease, operations.run(execution), publicationOwner)
    }

    /** A restored checkpoint already contains the plan proven for this exact request and lease. */
    private suspend fun admitPlan(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        publicationOwner: QueryExecutionClaim?,
        budget: QueryBudget,
    ): Refinement<QueryPlanAcquisition, QueryRunRejection> {
        val retained =
            when (val restored = restoreInputs(request, lease, publicationOwner)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        val admissionAuthority = admissionAuthority(request, lease)
        val source = request.from
        val impact =
            if (source is QueryFromDocument.Impact) {
                when (
                    val admitted = source.investigation.admitImpact(lease, admissionAuthority, producerSeeds, budget)
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            } else null
        val syntax =
            when (
                val admitted =
                    request
                        .admitSyntax(
                            lease,
                            admissionAuthority,
                            retained,
                            if (source is QueryFromDocument.Impact && impact != null) mapOf(source to impact.source)
                            else emptyMap(),
                        )
                        .refinePlanSyntax()
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return when (val admitted = QueryPlanCompiler.admit(syntax)) {
            is QueryPlanAdmission.Admitted ->
                Refinement.Refined(QueryPlanAcquisition(admitted.plan, impact?.examinedWork ?: 0L))
            is QueryPlanAdmission.Rejected -> Refinement.Rejected(admitted.failure.protocolRejection())
        }
    }

    private fun restoreInputs(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<Map<QueryFromDocument.Result, QueryRetainedResult>, QueryRunRejection> {
        val retained = linkedMapOf<QueryFromDocument.Result, QueryRetainedResult>()
        for (source in request.resultInputs()) {
            if (source in retained) continue
            when (val restored = state.restoreResult(source.reference, lease, publicationOwner)) {
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
        budget: QueryBudget,
        examinedWork: Long = 0L,
    ): Refinement<io.github.amichne.kast.kernel.ResourceBudget, QueryRunRejection> =
        when (val remaining = authority.remainingReadBudget(budget.resources)) {
            is Refinement.Refined ->
                when (
                    val work =
                        WorkUnitLimit.parse(
                            minOf(
                                remaining.value.workUnitLimit.value,
                                budget.resources.workUnitLimit.value - examinedWork,
                            )
                        )
                ) {
                    is Refinement.Refined -> Refinement.Refined(remaining.value.copy(workUnitLimit = work.value))
                    is Refinement.Rejected ->
                        Refinement.Rejected(
                            QueryRunRejection.ImpactSourceRejected(
                                QueryImpactSourceFailureDocument.Admission(
                                    QueryImpactSourceFailureCode.WORK_LIMIT_REACHED,
                                    queryPosition(0),
                                )
                            )
                        )
                }
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
        val impactReferences =
            (request.from as? QueryFromDocument.Impact)
                ?.investigation
                ?.let {
                    it.seeds.values.flatMap { seed -> listOf(seed.enclosing, seed.callable) } +
                        it.declarations.values.map { declaration -> declaration.reference }
                }
                .orEmpty()
        val references = sourceReferences + concatenatedReferences + impactReferences
        return if (references.isEmpty()) authority else authority.admitReadReferences(references, lease)
    }

    private fun rejected(reason: QueryExecutionRejectionDocument): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
}

private data class QueryPlanAcquisition(val plan: AdmittedQueryPlan, val examinedWork: Long)

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
