package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.ExactSelectorLookup
import io.github.amichne.kast.runtime.composition.protocol.RelationEndpointIssuance
import io.github.amichne.kast.runtime.composition.protocol.RelationSubjectLookup
import io.github.amichne.kast.runtime.composition.protocol.protocolDocument
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.relation.contract.RelationReadRejection as DomainRelationRejection
import io.github.amichne.kast.relation.contract.RelationReadResult as DomainRelationResult
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.traversal.contract.TraversalResult as DomainTraversalResult

private const val SEMANTIC_WORK_MULTIPLIER = 100L
private const val SEMANTIC_TIME_MILLIS = 30_000L
private const val SEMANTIC_RETURNED_BYTES = 1_048_576L

internal class CanonicalRelationReadHandler(
    private val operations: RelationOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<
    RelationReadRequest,
    RelationReadResult,
    RelationReadQualification,
    RelationReadRejection,
    > {
    override suspend fun execute(request: RelationReadRequest): OperationOutcome<
        RelationReadResult,
        RelationReadQualification,
        RelationReadRejection,
        > {
        val budget = when (val admitted = relationBudget(request.limit.value)) {
            is RelationBudgetAdmission.Admitted -> admitted.budget
            RelationBudgetAdmission.Rejected ->
                return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
        }
        val meaning = request.relation.meaning()
        val domainRequest = when (val subject = authority.relationSubject(request.exactSelector)) {
            is RelationSubjectLookup.Selector ->
                DomainRelationRequest.start(subject.selector, meaning, budget)
            RelationSubjectLookup.Missing ->
                return OperationOutcome.Rejected(RelationReadRejection.SELECTOR_STALE)
        }
        return when (val result = operations.read(domainRequest)) {
            is DomainRelationResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainRelationResult.Complete -> project(
                result.batch.facts,
                result.batch.request.subject,
                RelationProjection.Complete,
            )
            is DomainRelationResult.Qualified -> project(
                result.batch.facts,
                result.batch.request.subject,
                RelationProjection.Qualified(result.coverage.limitations),
            )
        }
    }

    private fun project(
        facts: List<RelationFact>,
        subject: RelationEndpoint,
        projection: RelationProjection,
    ): OperationOutcome<RelationReadResult, RelationReadQualification, RelationReadRejection> {
        val documents = linkedMapOf<String, io.github.amichne.kast.protocol.contract.SymbolDocument>()
        facts.forEach { fact ->
            val endpoint = fact.relatedTo(subject)
            val issued = when (val result = authority.issueEndpoint(endpoint)) {
                is RelationEndpointIssuance.Issued -> result.selector
                is RelationEndpointIssuance.Rejected ->
                    return OperationOutcome.Rejected(RelationReadRejection.SELECTOR_STALE)
            }
            val document = endpoint.protocolDocument(issued)
                           ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
            documents.putIfAbsent(issued.value, document)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents.values.toList())) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.RELATION_READ.id,
            subject.lease.generation,
            RelationReadResult(bounded),
        )
        return when (projection) {
            RelationProjection.Complete -> OperationOutcome.Complete(envelope)
            is RelationProjection.Qualified -> OperationOutcome.Qualified(
                envelope,
                if (RelationLimitation.RESULT_LIMIT_REACHED in projection.limitations) {
                    RelationReadQualification.RESULT_LIMIT
                } else {
                    RelationReadQualification.COVERAGE_INCOMPLETE
                },
            )
        }
    }
}

internal class CanonicalTraversalRunHandler(
    private val operations: TraversalOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<
    TraversalRunRequest,
    TraversalRunResult,
    TraversalRunQualification,
    TraversalRunRejection,
    > {
    override suspend fun execute(request: TraversalRunRequest): OperationOutcome<
        TraversalRunResult,
        TraversalRunQualification,
        TraversalRunRejection,
        > {
        val selector = when (val lookup = authority.exact(request.exactSelector)) {
            is ExactSelectorLookup.Found -> lookup.selector
            ExactSelectorLookup.Missing ->
                return OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE)
        }
        val budget = when (
            val admitted = traversalBudget(
                request.maximumDepth.value,
                request.maximumResults.value,
            )
        ) {
            is TraversalBudgetAdmission.Admitted -> admitted.budget
            TraversalBudgetAdmission.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        val plan = when (val admitted = TraversalPlan.start(selector, request.relation.meaning(), budget)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        return when (val result = operations.run(plan)) {
            is DomainTraversalResult.Rejected -> OperationOutcome.Rejected(
                result.reason.protocol(),
            )
            is DomainTraversalResult.Complete -> projectTraversal(
                result.page.records.map { it.related },
                plan,
                TraversalProjection.Complete,
            )
            is DomainTraversalResult.Qualified -> projectTraversal(
                result.page.records.map { it.related },
                plan,
                TraversalProjection.Qualified(result.qualification.limitations),
            )
        }
    }

    private fun projectTraversal(
        endpoints: List<RelationEndpoint.Resolved>,
        plan: TraversalPlan,
        projection: TraversalProjection,
    ): OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
        val documents = linkedMapOf<String, io.github.amichne.kast.protocol.contract.SymbolDocument>()
        endpoints.forEach { endpoint ->
            val issued = when (val result = authority.issueEndpoint(endpoint)) {
                is RelationEndpointIssuance.Issued -> result.selector
                is RelationEndpointIssuance.Rejected ->
                    return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
            val document = endpoint.protocolDocument(issued)
                           ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            documents.putIfAbsent(issued.value, document)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents.values.toList())) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.TRAVERSAL_RUN.id,
            plan.start.lease.generation,
            TraversalRunResult(bounded),
        )
        return when (projection) {
            TraversalProjection.Complete -> OperationOutcome.Complete(envelope)
            is TraversalProjection.Qualified -> OperationOutcome.Qualified(
                envelope,
                when {
                    TraversalLimitation.DEPTH_LIMIT_REACHED in projection.limitations ->
                        TraversalRunQualification.DEPTH_LIMIT
                    TraversalLimitation.RECORD_LIMIT_REACHED in projection.limitations ->
                        TraversalRunQualification.RESULT_LIMIT
                    else -> TraversalRunQualification.COVERAGE_INCOMPLETE
                },
            )
        }
    }
}

private sealed interface RelationProjection {
    data object Complete : RelationProjection
    data class Qualified(val limitations: Set<RelationLimitation>) : RelationProjection
}

private sealed interface TraversalProjection {
    data object Complete : TraversalProjection
    data class Qualified(val limitations: Set<TraversalLimitation>) : TraversalProjection
}

private sealed interface RelationBudgetAdmission {
    data class Admitted(val budget: RelationBudget) : RelationBudgetAdmission
    data object Rejected : RelationBudgetAdmission
}

private sealed interface TraversalBudgetAdmission {
    data class Admitted(val budget: TraversalBudget) : TraversalBudgetAdmission
    data object Rejected : TraversalBudgetAdmission
}

private sealed interface BudgetValue<out Value> {
    data class Refined<Value>(val value: Value) : BudgetValue<Value>
    data object Rejected : BudgetValue<Nothing>
}

/**
 * Proof transition: `Int -> RelationBudgetAdmission`.
 *
 * Admitted establishes positive record, work, elapsed, and byte bounds. Rejected closes every
 * refinement failure. Raw count extraction is confined to this protocol boundary.
 */
private fun relationBudget(rawLimit: Int): RelationBudgetAdmission {
    val results = when (val value = ResultLimit.parse(rawLimit).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
    }
    val work = when (
        val value = WorkUnitLimit.parse(rawLimit * SEMANTIC_WORK_MULTIPLIER).budgetValue()
    ) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
    }
    val elapsed = when (val value = ElapsedTimeLimitMillis.parse(SEMANTIC_TIME_MILLIS).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
    }
    val bytes = when (val value = RelationByteLimit.parse(SEMANTIC_RETURNED_BYTES).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
    }
    return RelationBudgetAdmission.Admitted(
        RelationBudget(ResourceBudget(results, work, elapsed), bytes),
    )
}

/**
 * Proof transition: `(Int, Int) -> TraversalBudgetAdmission`.
 *
 * Admitted establishes aggregate and one-hop bounds where no one-hop authority exceeds its
 * traversal bound. Rejected closes every numeric refinement failure. Raw counts remain here.
 */
private fun traversalBudget(
    rawDepth: Int,
    rawResults: Int,
): TraversalBudgetAdmission {
    val relation = when (val admitted = relationBudget(rawResults)) {
        is RelationBudgetAdmission.Admitted -> admitted.budget
        RelationBudgetAdmission.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    val records = when (val value = ResultLimit.parse(rawResults).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    val bytes = when (val value = TraversalByteLimit.parse(SEMANTIC_RETURNED_BYTES).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    val work = when (
        val value = WorkUnitLimit.parse(rawResults * SEMANTIC_WORK_MULTIPLIER).budgetValue()
    ) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    val elapsed = when (val value = ElapsedTimeLimitMillis.parse(SEMANTIC_TIME_MILLIS).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    val depth = when (val value = TraversalDepthLimit.parse(rawDepth).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    val frontier = when (val value = TraversalFrontierLimit.parse(rawResults).budgetValue()) {
        is BudgetValue.Refined -> value.value
        BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
    }
    return TraversalBudgetAdmission.Admitted(
        TraversalBudget(records, bytes, work, elapsed, depth, frontier, relation),
    )
}

/**
 * Proof transition: `Refinement<Value, Failure> -> BudgetValue<Value>`.
 *
 * Preserves a refined numeric capability or closes every expected parser failure as
 * [BudgetValue.Rejected]. Raw numeric extraction remains in the enclosing budget admission.
 */
private fun <Value, Failure> Refinement<Value, Failure>.budgetValue(): BudgetValue<Value> = when (this) {
    is Refinement.Refined -> BudgetValue.Refined(value)
    is Refinement.Rejected -> BudgetValue.Rejected
}

private fun RelationKindDocument.meaning(): RelationMeaning = when (this) {
    RelationKindDocument.REFERENCES -> RelationMeaning.References
    RelationKindDocument.CALLERS -> RelationMeaning.Callers
    RelationKindDocument.CALLEES -> RelationMeaning.Callees
    RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
    RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
    RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
    RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
}

private fun RelationFact.relatedTo(subject: RelationEndpoint): RelationEndpoint =
    if (source.fingerprint == subject.fingerprint) target else source

private fun DomainRelationRejection.protocol(): RelationReadRejection = when (this) {
    DomainRelationRejection.WORKSPACE_NOT_READY -> RelationReadRejection.WORKSPACE_NOT_READY
    DomainRelationRejection.WORKSPACE_ROOT_MISMATCH,
    DomainRelationRejection.STALE_GENERATION,
    DomainRelationRejection.STALE_SELECTOR,
        -> RelationReadRejection.SELECTOR_STALE
    DomainRelationRejection.UNSUPPORTED_SUBJECT -> RelationReadRejection.RELATION_UNSUPPORTED
    DomainRelationRejection.SCOPE_REJECTED,
    DomainRelationRejection.WORKSPACE_INDEX_UNAVAILABLE,
    DomainRelationRejection.OUTSIDE_SCOPE,
    DomainRelationRejection.AMBIGUOUS_SUBJECT,
    DomainRelationRejection.COMPILER_IDENTITY_UNAVAILABLE,
    DomainRelationRejection.COMPILER_CONTRACT_VIOLATION,
        -> RelationReadRejection.RELATION_UNSUPPORTED
}

private fun TraversalRejection.protocol(): TraversalRunRejection = when (this) {
    is TraversalRejection.OneHopRejected -> when (reason) {
        DomainRelationRejection.WORKSPACE_NOT_READY -> TraversalRunRejection.WORKSPACE_NOT_READY
        DomainRelationRejection.WORKSPACE_ROOT_MISMATCH,
        DomainRelationRejection.STALE_GENERATION,
        DomainRelationRejection.STALE_SELECTOR,
            -> TraversalRunRejection.SELECTOR_STALE
        else -> TraversalRunRejection.PLAN_REJECTED
    }
    TraversalRejection.ReaderContractViolation,
    TraversalRejection.TraversalContractViolation,
        -> TraversalRunRejection.PLAN_REJECTED
}
