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
    peerSiteAdmissions: List<QueryImpactPeerSiteAdmission> = emptyList(),
) {
    private var pageAdmission = PageAdmission.PUBLIC

    private val peerSiteAdmissions = java.util.Collections.unmodifiableList(peerSiteAdmissions.toList())
    private val pagePublication = QueryPagePublication(state, publication)
    private val projection = QueryOutcomeProjection(authority, state, retentionObservation)

    /** Shared automatic entry point for independent rows; investigation-ledger outputs retain their own accounting. */
    suspend fun executeAutomatically(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
        policy: QueryInvocationPolicy,
    ): QueryPublishedPage {
        when (val admission = automaticOutputAdmission(request)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return OperationOutcome.Rejected(admission.failure)
        }
        if (
            request !is QueryRunRequest.Run ||
                request.output == QueryOutputDocument.ValuePaths ||
                request.output is QueryOutputDocument.ImpactWitness
        )
            return execute(request, lease, budget)
        val recording = QueryInvocationExecution(operations)
        val pages =
            CanonicalQueryProtocol(
                    operations = recording,
                    authority = authority,
                    state = state,
                    publication = QueryExecutionPublication.Immediate,
                    producerSeeds = producerSeeds,
                    retentionObservation = retentionObservation,
                    peerSiteAdmissions = peerSiteAdmissions,
                )
                .also { it.pageAdmission = PageAdmission.AUTOMATIC_INVOCATION }
        val accumulated =
            when (
                val execution =
                    AutomaticSymbolQueryRunner(state, lease, policy) { action, remaining ->
                            recording.page(remaining) { pages.execute(action, lease, remaining) }
                        }
                        .run(request, budget)
            ) {
                is Refinement.Refined -> execution.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(execution.failure)
            }
        return when (val acquired = state.acquireInitial(lease)) {
            is QueryInitialAcquisition.Acquired ->
                pagePublication.execute(acquired.claim) {
                    QueryInvocationProjection(authority, state, retentionObservation)
                        .project(request, lease, accumulated, policy, acquired.claim)
                }
            QueryInitialAcquisition.Unavailable -> rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            QueryInitialAcquisition.CapacityExceeded ->
                rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
        }
    }

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
                        pagePublication.execute(acquired.claim) {
                            projection.readRetained(request, lease, acquired.claim, budget.resources.resultLimit)
                        }
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
            is QueryCheckpointAcquisition.Published -> pagePublication.execute(acquired.claim) { acquired.page }
            QueryCheckpointAcquisition.InUse -> rejected(QueryExecutionRejectionDocument.CONTINUATION_IN_USE)
            QueryCheckpointAcquisition.Unavailable -> rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            QueryCheckpointAcquisition.Mismatch -> rejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
            QueryCheckpointAcquisition.CapacityExceeded ->
                rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
            is QueryCheckpointAcquisition.Rejected -> rejected(acquired.cause.rejection)
            is QueryCheckpointAcquisition.Acquired ->
                executeOwned(acquired.request, acquired.checkpoint, acquired.claim, lease, budget)
        }

    private suspend fun executeOutput(
        token: QueryExecutionContinuation.Output,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): QueryPublishedPage =
        when (val acquired = state.acquireOutput(token, lease, budget.returnedBytes.value)) {
            is QueryOutputAcquisition.Acquired -> pagePublication.execute(acquired.claim) { acquired.page }
            is QueryOutputAcquisition.Published -> pagePublication.execute(acquired.claim) { acquired.page }
            QueryOutputAcquisition.InUse -> rejected(QueryExecutionRejectionDocument.CONTINUATION_IN_USE)
            QueryOutputAcquisition.Unavailable -> rejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            QueryOutputAcquisition.Mismatch -> rejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
            QueryOutputAcquisition.CapacityExceeded ->
                rejected(QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED)
            is QueryOutputAcquisition.Rejected -> rejected(acquired.cause.rejection)
        }

    private suspend fun executeOwned(
        request: QueryRunRequest.Run,
        checkpoint: QueryCheckpoint?,
        claim: QueryExecutionClaim,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
    ): QueryPublishedPage {
        return pagePublication.execute(claim) { executeAdmitted(request, lease, budget, checkpoint, claim) }
    }

    private suspend fun executeAdmitted(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        budget: QueryBudget,
        checkpoint: QueryCheckpoint?,
        publicationOwner: QueryExecutionClaim? = null,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        when (val admitted = completionAdmission(request, pageAdmission)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return OperationOutcome.Rejected(admitted.failure)
        }
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
            when (val restored = QueryRetainedInputAdmission(state).restore(request, lease, publicationOwner)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        val source = request.from
        val peerSelections =
            when (val selected = source.peerSelections(lease)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return selected
            }
        val admissionAuthority = admissionAuthority(authority, request, lease, peerSelections)
        val impact =
            when (val admitted = admitImpactSource(source, lease, admissionAuthority, budget)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
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

    private suspend fun admitImpactSource(
        source: QueryFromDocument,
        lease: SemanticReadAuthority,
        admissionAuthority: QueryReferenceAuthority,
        budget: QueryBudget,
    ): Refinement<QueryImpactSourceAdmission?, QueryRunRejection> =
        if (source is QueryFromDocument.Impact)
            source.investigation.admitImpact(lease, admissionAuthority, producerSeeds, budget, peerSiteAdmissions)
        else Refinement.Refined(null)

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

    private fun rejected(reason: QueryExecutionRejectionDocument): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
}

private data class QueryPlanAcquisition(val plan: AdmittedQueryPlan, val examinedWork: Long)

private fun QueryFromDocument.peerSelections(
    lease: SemanticReadAuthority
): Refinement<List<QueryImpactPeerSelection>, QueryRunRejection> =
    if (this is QueryFromDocument.Impact) investigation.selectPeerSites(lease) else Refinement.Refined(emptyList())

private suspend fun admissionAuthority(
    authority: QueryReferenceAuthority,
    request: QueryRunRequest.Run,
    lease: SemanticReadAuthority,
    peers: List<QueryImpactPeerSelection>,
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
                    it.declarations.values
                        .filter { declaration -> peers.none { declaration in it.declarations } }
                        .map { declaration -> declaration.reference }
            }
            .orEmpty()
    val references = sourceReferences + concatenatedReferences + impactReferences
    return if (references.isEmpty()) authority else authority.admitReadReferences(references, lease)
}
