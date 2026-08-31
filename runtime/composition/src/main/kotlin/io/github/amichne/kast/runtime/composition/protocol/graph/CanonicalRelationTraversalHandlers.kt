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
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationFactCoverage
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
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
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.relation.contract.RelationReadRejection as DomainRelationRejection
import io.github.amichne.kast.relation.contract.RelationReadResult as DomainRelationResult
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.traversal.contract.TraversalResult as DomainTraversalResult

private const val SEMANTIC_WORK_MULTIPLIER = 100L
private const val SEMANTIC_TIME_MILLIS = 30_000L
private const val SEMANTIC_TRAVERSAL_HOP_TIME_MILLIS = 1_000L
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
                RelationProjection.Qualified(result.coverage),
            )
        }
    }

    private fun project(
        facts: List<RelationFact>,
        subject: RelationEndpoint,
        projection: RelationProjection,
    ): OperationOutcome<RelationReadResult, RelationReadQualification, RelationReadRejection> {
        val documents = mutableListOf<RelationFactDocument>()
        facts.forEach { fact ->
            val document = fact.protocolDocument(authority)
                ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
            documents += document
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents)) {
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
            is RelationProjection.Qualified -> {
                val qualification = projection.coverage.protocolQualification()
                    ?: return OperationOutcome.Rejected(
                        RelationReadRejection.RELATION_UNSUPPORTED,
                    )
                OperationOutcome.Qualified(envelope, qualification)
            }
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
                result.page.records,
                plan,
                TraversalProjection.Complete,
            )
            is DomainTraversalResult.Qualified -> projectTraversal(
                result.page.records,
                plan,
                TraversalProjection.Qualified(result.qualification),
            )
        }
    }

    private fun projectTraversal(
        records: List<TraversalRecord>,
        plan: TraversalPlan,
        projection: TraversalProjection,
    ): OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
        val documents = mutableListOf<TraversalRecordDocument>()
        records.forEach { record ->
            val relation = record.fact.protocolDocument(authority)
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            val depth = when (val admitted = TraversalDepthDocument.parse(record.depth.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
            documents += TraversalRecordDocument(depth, relation)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents)) {
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
            is TraversalProjection.Qualified -> {
                val qualification = projection.qualification.protocolQualification()
                    ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
                OperationOutcome.Qualified(envelope, qualification)
            }
        }
    }
}

private sealed interface RelationProjection {
    data object Complete : RelationProjection
    data class Qualified(val coverage: RelationIncompleteCoverage) : RelationProjection
}

private sealed interface TraversalProjection {
    data object Complete : TraversalProjection
    data class Qualified(val qualification: TraversalQualification) : TraversalProjection
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
 * Proof transition: `(Int, Long) -> RelationBudgetAdmission`.
 *
 * Admitted establishes positive record, work, elapsed, and byte bounds. Rejected closes every
 * refinement failure. Raw count extraction is confined to this protocol boundary.
 */
private fun relationBudget(
    rawLimit: Int,
    rawElapsedMillis: Long = SEMANTIC_TIME_MILLIS,
): RelationBudgetAdmission {
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
    val elapsed = when (val value = ElapsedTimeLimitMillis.parse(rawElapsedMillis).budgetValue()) {
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
    val relation = when (
        val admitted = relationBudget(rawResults, SEMANTIC_TRAVERSAL_HOP_TIME_MILLIS)
    ) {
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

private fun RelationFact.protocolDocument(
    authority: CanonicalProtocolAuthority,
): RelationFactDocument? {
    val occurrenceStart = ProtocolOffset.parse(occurrence.range.startInclusive).refinedOrNull()
        ?: return null
    val occurrenceEnd = ProtocolOffset.parse(occurrence.range.endExclusive).refinedOrNull()
        ?: return null
    val occurrenceRange = SourceRangeDocument.create(occurrenceStart, occurrenceEnd).refinedOrNull()
        ?: return null
    return RelationFactDocument(
        meaning = meaning.protocolDocument(),
        source = source.protocolDocument(authority) ?: return null,
        target = target.protocolDocument(authority) ?: return null,
        occurrence = RelationOccurrenceDocument(
            file = ProtocolText.parse(occurrence.file.stableValue).refinedOrNull() ?: return null,
            range = occurrenceRange,
        ),
        provenance = provenance.protocolDocument(),
        coverage = coverage.protocolDocument(),
    )
}

private fun RelationEndpoint.protocolDocument(
    authority: CanonicalProtocolAuthority,
): io.github.amichne.kast.protocol.contract.SymbolDocument? {
    val selector = when (val issued = authority.issueEndpoint(this)) {
        is RelationEndpointIssuance.Issued -> issued.selector
        is RelationEndpointIssuance.Rejected -> return null
    }
    return protocolDocument(selector)
}

private fun RelationMeaning.protocolDocument(): RelationKindDocument = when (this) {
    RelationMeaning.References -> RelationKindDocument.REFERENCES
    RelationMeaning.Callers -> RelationKindDocument.CALLERS
    RelationMeaning.Callees -> RelationKindDocument.CALLEES
    RelationMeaning.Implementations -> RelationKindDocument.IMPLEMENTATIONS
    RelationMeaning.Inheritors -> RelationKindDocument.INHERITORS
    RelationMeaning.Overrides -> RelationKindDocument.OVERRIDES
    RelationMeaning.TypeUses -> RelationKindDocument.TYPE_USES
}

private fun RelationProvenance.protocolDocument(): RelationProvenanceDocument = when (this) {
    RelationProvenance.K2_AUTHORED_SOURCE -> RelationProvenanceDocument.K2_AUTHORED_SOURCE
    RelationProvenance.K2_GENERATED_SOURCE -> RelationProvenanceDocument.K2_GENERATED_SOURCE
    RelationProvenance.K2_PROJECT_LIBRARY -> RelationProvenanceDocument.K2_PROJECT_LIBRARY
}

private fun RelationFactCoverage.protocolDocument(): RelationFactCoverageDocument = when (this) {
    RelationFactCoverage.EXACT_COMPILER_CONFIRMED ->
        RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
}

private fun RelationIncompleteCoverage.protocolQualification(): RelationReadQualification? {
    val minimum = RelationKnownMinimumDocument.parse(knownMinimum.value).refinedOrNull()
        ?: return null
    val continuation = RelationContinuationDocument.parse(continuation.fingerprint.value)
        .refinedOrNull() ?: return null
    return RelationReadQualification.create(
        minimum,
        limitations.map(RelationLimitation::protocolDocument),
        continuation,
    ).refinedOrNull()
}

private fun TraversalQualification.protocolQualification(): TraversalRunQualification? {
    val continuation = TraversalContinuationDocument.parse(continuation.fingerprint.value)
        .refinedOrNull() ?: return null
    return TraversalRunQualification.create(
        limitations.map(TraversalLimitation::protocolDocument),
        relationLimitations.map(RelationLimitation::protocolDocument),
        continuation,
    ).refinedOrNull()
}

private fun RelationLimitation.protocolDocument(): RelationLimitationDocument = when (this) {
    RelationLimitation.RESULT_LIMIT_REACHED -> RelationLimitationDocument.RESULT_LIMIT_REACHED
    RelationLimitation.BYTE_LIMIT_REACHED -> RelationLimitationDocument.BYTE_LIMIT_REACHED
    RelationLimitation.WORK_LIMIT_REACHED -> RelationLimitationDocument.WORK_LIMIT_REACHED
    RelationLimitation.TIME_LIMIT_REACHED -> RelationLimitationDocument.TIME_LIMIT_REACHED
    RelationLimitation.DUMB_MODE_TRANSITION -> RelationLimitationDocument.DUMB_MODE_TRANSITION
    RelationLimitation.UNRESOLVED_TARGET -> RelationLimitationDocument.UNRESOLVED_TARGET
    RelationLimitation.UNSUPPORTED_ITEM -> RelationLimitationDocument.UNSUPPORTED_ITEM
    RelationLimitation.PROVIDER_FAILURE -> RelationLimitationDocument.PROVIDER_FAILURE
    RelationLimitation.PROVIDER_INCOMPLETE -> RelationLimitationDocument.PROVIDER_INCOMPLETE
}

private fun TraversalLimitation.protocolDocument(): TraversalLimitationDocument = when (this) {
    TraversalLimitation.RECORD_LIMIT_REACHED -> TraversalLimitationDocument.RECORD_LIMIT_REACHED
    TraversalLimitation.BYTE_LIMIT_REACHED -> TraversalLimitationDocument.BYTE_LIMIT_REACHED
    TraversalLimitation.WORK_LIMIT_REACHED -> TraversalLimitationDocument.WORK_LIMIT_REACHED
    TraversalLimitation.TIME_LIMIT_REACHED -> TraversalLimitationDocument.TIME_LIMIT_REACHED
    TraversalLimitation.DEPTH_LIMIT_REACHED -> TraversalLimitationDocument.DEPTH_LIMIT_REACHED
    TraversalLimitation.FRONTIER_LIMIT_REACHED -> TraversalLimitationDocument.FRONTIER_LIMIT_REACHED
    TraversalLimitation.ONE_HOP_INCOMPLETE -> TraversalLimitationDocument.ONE_HOP_INCOMPLETE
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

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
    TraversalRejection.RequiredEvidenceUnavailable,
    TraversalRejection.RequiredEvidenceStale,
        -> TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED
    TraversalRejection.ReaderContractViolation,
    TraversalRejection.TraversalContractViolation,
        -> TraversalRunRejection.PLAN_REJECTED
}
