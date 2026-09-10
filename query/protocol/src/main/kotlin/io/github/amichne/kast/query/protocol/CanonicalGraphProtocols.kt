package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

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
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
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
import io.github.amichne.kast.query.protocol.CandidateSelectorTokenIssuance
import io.github.amichne.kast.query.protocol.ExactSelectorLookup
import io.github.amichne.kast.query.protocol.RelationEndpointIssuance
import io.github.amichne.kast.query.protocol.RelationSubjectLookup
import io.github.amichne.kast.query.protocol.SelectorLookupRejection
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
import io.github.amichne.kast.traversal.contract.TraversalPlanResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalResumeFailure
import io.github.amichne.kast.relation.contract.RelationReadRejection as DomainRelationRejection
import io.github.amichne.kast.relation.contract.RelationReadResult as DomainRelationResult
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.relation.contract.RelationResumeFailure as DomainRelationResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalResult as DomainTraversalResult


class CanonicalRelationReadProtocol(
    private val operations: RelationOperations,
    private val authority: QueryReferenceAuthority,
) {
    suspend fun execute(request: RelationReadRequest, current: SemanticReadAuthority, maximum: RelationBudget): OperationOutcome<
        RelationReadResult,
        RelationReadQualification,
        RelationReadRejection,
        > {
        val resultLimit = ResultLimit.parse(minOf(request.limit.value, maximum.resources.resultLimit.value)).refinedOrNull()
            ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
        val budget = maximum.copy(resources = maximum.resources.copy(resultLimit = resultLimit))
        val meaning = request.relation.meaning()
        val subject = when (val lookup = authority.exact(request.exactSelector, current)) {
            is ExactSelectorLookup.Found -> lookup.selector
            is ExactSelectorLookup.Rejected ->
                return OperationOutcome.Rejected(lookup.reason.relationProtocol())
        }
        val domainRequest = when (val position = request.position) {
            RelationReadPositionDocument.Start ->
                DomainRelationRequest.start(subject, meaning, budget)
            is RelationReadPositionDocument.Resume -> {
                val continuation = when (
                    val decoded = CanonicalRelationContinuationCodec.decode(position.continuation, subject.lease)
                ) {
                    is CanonicalRelationContinuationDecoding.Decoded -> decoded.continuation
                    CanonicalRelationContinuationDecoding.AuthorityMismatch ->
                        return OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_GENERATION_MISMATCH)
                    CanonicalRelationContinuationDecoding.Malformed ->
                        return OperationOutcome.Rejected(
                            RelationReadRejection.CONTINUATION_MALFORMED,
                        )
                }
                when (
                    val admitted = DomainRelationRequest.resume(
                        subject,
                        meaning,
                        budget,
                        continuation,
                    )
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return OperationOutcome.Rejected(admitted.failure.protocol())
                }
            }
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
            subject.lease.evidenceBasis(),
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

class CanonicalTraversalRunProtocol(
    private val operations: TraversalOperations,
    private val authority: QueryReferenceAuthority,
) {
    suspend fun execute(request: TraversalRunRequest, current: SemanticReadAuthority, maximum: TraversalBudget): OperationOutcome<
        TraversalRunResult,
        TraversalRunQualification,
        TraversalRunRejection,
        > {
        val selector = when (val lookup = authority.exact(request.exactSelector, current)) {
            is ExactSelectorLookup.Found -> lookup.selector
            is ExactSelectorLookup.Rejected ->
                return OperationOutcome.Rejected(lookup.reason.traversalProtocol())
        }
        if (request.maximumDepth.value > maximum.depth.value) return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val records = ResultLimit.parse(minOf(request.maximumResults.value, maximum.records.value)).refinedOrNull()
            ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val depth = TraversalDepthLimit.parse(request.maximumDepth.value).refinedOrNull()
            ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val oneHopRecords = ResultLimit.parse(minOf(records.value, maximum.oneHop.resources.resultLimit.value)).refinedOrNull()
            ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val budget = maximum.copy(records = records, depth = depth,
            oneHop = maximum.oneHop.copy(resources = maximum.oneHop.resources.copy(resultLimit = oneHopRecords)))
        val meaning = request.relation.meaning()
        val plan = when (val position = request.position) {
            TraversalRunPositionDocument.Start -> when (
                val admitted = TraversalPlan.start(selector, meaning, budget)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
            is TraversalRunPositionDocument.Resume -> {
                val continuation = when (
                    val decoded = CanonicalTraversalContinuationCodec.decode(
                        position.continuation,
                        budget,
                        authority,
                        current,
                    )
                ) {
                    is CanonicalTraversalContinuationDecoding.Decoded -> decoded.continuation
                    CanonicalTraversalContinuationDecoding.SubjectMismatch ->
                        return OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH)
                    CanonicalTraversalContinuationDecoding.AuthorityMismatch ->
                        return OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH)
                    CanonicalTraversalContinuationDecoding.Malformed ->
                        return OperationOutcome.Rejected(
                            TraversalRunRejection.CONTINUATION_MALFORMED,
                        )
                }
                when (val admitted = TraversalPlan.resume(selector, meaning, budget, continuation)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return OperationOutcome.Rejected(
                        admitted.failure.protocol(),
                    )
                }
            }
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
        val snapshotRoot = when (
            val admitted = ProtocolText.parse(plan.start.lease.workspaceRoot.value)
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.TRAVERSAL_RUN.id,
            plan.start.lease.evidenceBasis(),
            TraversalRunResult(snapshotRoot, bounded),
        )
        return when (projection) {
            TraversalProjection.Complete -> OperationOutcome.Complete(envelope)
            is TraversalProjection.Qualified -> {
                val qualification = projection.qualification.protocolQualification(authority)
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

private fun RelationKindDocument.meaning(): RelationMeaning = when (this) {
    RelationKindDocument.REFERENCES -> RelationMeaning.References
    RelationKindDocument.CALLERS -> RelationMeaning.Callers
    RelationKindDocument.CALLEES -> RelationMeaning.Callees
    RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
    RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
    RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
    RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
}

private fun RelationIncompleteCoverage.protocolQualification(): RelationReadQualification? {
    val minimum = RelationKnownMinimumDocument.parse(knownMinimum.value).refinedOrNull()
        ?: return null
    val protocolLimitations = limitations.map(RelationLimitation::protocolDocument)
    return when (this) {
        is RelationIncompleteCoverage.Resumable -> {
            val document = CanonicalRelationContinuationCodec.encode(continuation) ?: return null
            RelationReadQualification.resumable(
                minimum,
                protocolLimitations,
                document,
            ).refinedOrNull()
        }
        is RelationIncompleteCoverage.TerminalIncomplete ->
            RelationReadQualification.terminalIncomplete(
                minimum,
                protocolLimitations,
            ).refinedOrNull()
    }
}

private fun TraversalQualification.protocolQualification(
    authority: QueryReferenceAuthority,
): TraversalRunQualification? = when (this) {
    is TraversalQualification.Resumable -> {
        val document = CanonicalTraversalContinuationCodec.encode(continuation, authority)
            ?: return null
        TraversalRunQualification.resumable(
            limitations.map(TraversalLimitation::protocolDocument),
            relationLimitations.map(RelationLimitation::protocolDocument),
            document,
        ).refinedOrNull()
    }
    is TraversalQualification.TerminalIncomplete ->
        TraversalRunQualification.terminalIncomplete(
            limitations.map(TraversalLimitation::protocolDocument),
            relationLimitations.map(RelationLimitation::protocolDocument),
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
    DomainRelationRejection.WORKSPACE_ROOT_MISMATCH ->
        RelationReadRejection.SELECTOR_WORKSPACE_MISMATCH
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
    DomainRelationRejection.CONTINUATION_CURSOR_MOVED ->
        RelationReadRejection.CONTINUATION_CURSOR_MOVED
}

private fun DomainRelationResumeFailure.protocol(): RelationReadRejection = when (this) {
    DomainRelationResumeFailure.SUBJECT_MISMATCH ->
        RelationReadRejection.CONTINUATION_SUBJECT_MISMATCH
    DomainRelationResumeFailure.MEANING_MISMATCH,
    DomainRelationResumeFailure.PROVIDER_MISMATCH,
        -> RelationReadRejection.CONTINUATION_RELATION_MISMATCH
    DomainRelationResumeFailure.SCOPE_MISMATCH ->
        RelationReadRejection.CONTINUATION_SCOPE_MISMATCH
    DomainRelationResumeFailure.GENERATION_MISMATCH ->
        RelationReadRejection.CONTINUATION_GENERATION_MISMATCH
}

private fun TraversalRejection.protocol(): TraversalRunRejection = when (this) {
    is TraversalRejection.OneHopRejected -> when (reason) {
        DomainRelationRejection.WORKSPACE_NOT_READY -> TraversalRunRejection.WORKSPACE_NOT_READY
        DomainRelationRejection.WORKSPACE_ROOT_MISMATCH ->
            TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH
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

private fun SelectorLookupRejection.relationProtocol(): RelationReadRejection = when (this) {
    SelectorLookupRejection.WRONG_KIND -> RelationReadRejection.SELECTOR_WRONG_KIND
    SelectorLookupRejection.MALFORMED -> RelationReadRejection.SELECTOR_MALFORMED
    SelectorLookupRejection.STALE -> RelationReadRejection.SELECTOR_STALE
    SelectorLookupRejection.WORKSPACE_MISMATCH -> RelationReadRejection.SELECTOR_WORKSPACE_MISMATCH
}

private fun SelectorLookupRejection.traversalProtocol(): TraversalRunRejection = when (this) {
    SelectorLookupRejection.WRONG_KIND -> TraversalRunRejection.SELECTOR_WRONG_KIND
    SelectorLookupRejection.MALFORMED -> TraversalRunRejection.SELECTOR_MALFORMED
    SelectorLookupRejection.STALE -> TraversalRunRejection.SELECTOR_STALE
    SelectorLookupRejection.WORKSPACE_MISMATCH -> TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH
}

private fun TraversalPlanResumeFailure.protocol(): TraversalRunRejection = when (this) {
    is TraversalPlanResumeFailure.Plan -> TraversalRunRejection.PLAN_REJECTED
    is TraversalPlanResumeFailure.Resume -> when (failure) {
        TraversalResumeFailure.SUBJECT_MISMATCH ->
            TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH
        TraversalResumeFailure.MEANING_MISMATCH ->
            TraversalRunRejection.CONTINUATION_RELATION_MISMATCH
        TraversalResumeFailure.SCOPE_MISMATCH ->
            TraversalRunRejection.CONTINUATION_SCOPE_MISMATCH
        TraversalResumeFailure.GENERATION_MISMATCH ->
            TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH
        TraversalResumeFailure.IDENTITY_MISMATCH ->
            TraversalRunRejection.CONTINUATION_MALFORMED
    }
}
