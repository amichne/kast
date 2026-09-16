package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadRejection as DomainRelationRejection
import io.github.amichne.kast.relation.contract.RelationReadResult as DomainRelationResult
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.relation.contract.RelationResumeFailure as DomainRelationResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPlanResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResumeFailure
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

class CanonicalRelationReadProtocol(
    private val operations: RelationOperations,
    private val authority: QueryReferenceAuthority,
) {
    suspend fun execute(
        request: RelationReadRequest,
        current: SemanticReadAuthority,
        maximum: RelationBudget,
    ): OperationOutcome<
        RelationReadResult,
        RelationReadQualification,
        RelationReadRejection,
    > {
        val resultLimit =
            ResultLimit.parse(minOf(request.limit.value, maximum.resources.resultLimit.value)).refinedOrNull()
                ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
        val meaning = request.relation.graphMeaning()
        val subject =
            when (
                val lookup =
                    if (request.position == RelationReadPositionDocument.Start)
                        authority.acquireReadExact(request.exactSelector, current)
                    else authority.exact(request.exactSelector, current)
            ) {
                is ExactSelectorLookup.Found -> lookup.selector
                is ExactSelectorLookup.Rejected -> return OperationOutcome.Rejected(lookup.reason.relationProtocol())
            }
        val resources =
            when (val remaining = authority.remainingReadBudget(maximum.resources)) {
                is Refinement.Refined -> remaining.value
                is Refinement.Rejected ->
                    return OperationOutcome.Rejected(
                        remaining.failure
                            .decodingFailure()
                            .lookupRejection(request.exactSelector, true)
                            .relationProtocol()
                    )
            }
        val budget = maximum.copy(resources = resources.copy(resultLimit = resultLimit))
        val domainRequest =
            when (val position = request.position) {
                RelationReadPositionDocument.Start -> DomainRelationRequest.start(subject, meaning, budget)
                is RelationReadPositionDocument.Resume -> {
                    val continuation =
                        when (
                            val decoded =
                                CanonicalRelationContinuationCodec.decode(position.continuation, subject.lease)
                        ) {
                            is CanonicalRelationContinuationDecoding.Decoded -> decoded.continuation
                            CanonicalRelationContinuationDecoding.AuthorityMismatch ->
                                return OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_GENERATION_MISMATCH)
                            CanonicalRelationContinuationDecoding.Malformed ->
                                return OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_MALFORMED)
                        }
                    when (
                        val admitted =
                            DomainRelationRequest.resume(
                                subject,
                                meaning,
                                budget,
                                continuation,
                            )
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return OperationOutcome.Rejected(admitted.failure.protocol())
                    }
                }
            }
        return when (val result = operations.read(domainRequest)) {
            is DomainRelationResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainRelationResult.Complete ->
                project(
                    result.batch,
                    RelationProjection.Complete,
                )
            is DomainRelationResult.Qualified ->
                project(
                    result.batch,
                    RelationProjection.Qualified(result.coverage),
                )
        }
    }

    private fun project(
        batch: io.github.amichne.kast.relation.contract.RelationBatch,
        projection: RelationProjection,
    ): OperationOutcome<RelationReadResult, RelationReadQualification, RelationReadRejection> {
        val subject = batch.request.subject
        val documents = mutableListOf<RelationFactDocument>()
        batch.facts.forEach { fact ->
            val document =
                fact.protocolDocument(authority)
                    ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
            documents += document
        }
        val bounded =
            when (val admitted = BoundedProtocolList.create(documents)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
            }
        val envelope =
            EvidenceEnvelope(
                CanonicalOperation.RELATION_READ.id,
                subject.lease.evidenceBasis(),
                RelationReadResult(
                    bounded,
                    batch.protocolOmissions(
                        when (projection) {
                            RelationProjection.Complete -> emptySet()
                            is RelationProjection.Qualified -> projection.coverage.limitations
                        }
                    ) ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED),
                    referenceAcquisitions = authority.readAcquisitions(),
                ),
            )
        return when (projection) {
            RelationProjection.Complete -> OperationOutcome.Complete(envelope)
            is RelationProjection.Qualified -> {
                val qualification =
                    projection.coverage.protocolQualification()
                        ?: return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
                OperationOutcome.Qualified(envelope, qualification)
            }
        }
    }
}

private sealed interface RelationProjection {
    data object Complete : RelationProjection

    data class Qualified(val coverage: RelationIncompleteCoverage) : RelationProjection
}

internal sealed interface TraversalProjection {
    data object Complete : TraversalProjection

    data class Qualified(val qualification: TraversalQualification) : TraversalProjection
}

internal fun RelationKindDocument.graphMeaning(): RelationMeaning =
    when (this) {
        RelationKindDocument.REFERENCES -> RelationMeaning.References
        RelationKindDocument.CALLERS -> RelationMeaning.Callers
        RelationKindDocument.CALLEES -> RelationMeaning.Callees
        RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
        RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
        RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
        RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
    }

private fun RelationIncompleteCoverage.protocolQualification(): RelationReadQualification? {
    val minimum = RelationKnownMinimumDocument.parse(knownMinimum.value).refinedOrNull() ?: return null
    val protocolLimitations = limitations.map(RelationLimitation::protocolDocument)
    return when (this) {
        is RelationIncompleteCoverage.Resumable -> {
            val document = CanonicalRelationContinuationCodec.encode(continuation) ?: return null
            RelationReadQualification.resumable(
                    minimum,
                    protocolLimitations,
                    document,
                )
                .refinedOrNull()
        }
        is RelationIncompleteCoverage.TerminalIncomplete ->
            RelationReadQualification.terminalIncomplete(
                    minimum,
                    protocolLimitations,
                )
                .refinedOrNull()
    }
}

internal fun TraversalQualification.protocolQualification(
    authority: QueryReferenceAuthority,
    emittedRecords: Int,
): TraversalRunQualification? =
    when (this) {
        is TraversalQualification.Resumable -> {
            val document = CanonicalTraversalContinuationCodec.encode(continuation, authority) ?: return null
            TraversalRunQualification.resumable(
                    limitations.map(TraversalLimitation::protocolDocument),
                    relationLimitations.map(RelationLimitation::protocolDocument),
                    document,
                    if (emittedRecords == 0)
                        io.github.amichne.kast.protocol.contract.ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET
                    else io.github.amichne.kast.protocol.contract.ReadResumeActionDocument.RESUME,
                )
                .refinedOrNull()
        }
        is TraversalQualification.TerminalIncomplete ->
            TraversalRunQualification.terminalIncomplete(
                    limitations.map(TraversalLimitation::protocolDocument),
                    relationLimitations.map(RelationLimitation::protocolDocument),
                )
                .refinedOrNull()
    }

internal fun RelationLimitation.protocolDocument(): RelationLimitationDocument =
    when (this) {
        RelationLimitation.RESULT_LIMIT_REACHED -> RelationLimitationDocument.RESULT_LIMIT_REACHED
        RelationLimitation.BYTE_LIMIT_REACHED -> RelationLimitationDocument.BYTE_LIMIT_REACHED
        RelationLimitation.WORK_LIMIT_REACHED -> RelationLimitationDocument.WORK_LIMIT_REACHED
        RelationLimitation.TIME_LIMIT_REACHED -> RelationLimitationDocument.TIME_LIMIT_REACHED
        RelationLimitation.DUMB_MODE_TRANSITION -> RelationLimitationDocument.DUMB_MODE_TRANSITION
        RelationLimitation.UNRESOLVED_TARGET -> RelationLimitationDocument.UNRESOLVED_TARGET
        RelationLimitation.UNSUPPORTED_ITEM -> RelationLimitationDocument.UNSUPPORTED_ITEM
        RelationLimitation.PROVIDER_FAILURE -> RelationLimitationDocument.PROVIDER_FAILURE
        RelationLimitation.PROVIDER_INCOMPLETE -> RelationLimitationDocument.PROVIDER_INCOMPLETE
        RelationLimitation.PROVIDER_STALLED -> RelationLimitationDocument.PROVIDER_STALLED
    }

internal fun TraversalLimitation.protocolDocument(): TraversalLimitationDocument =
    when (this) {
        TraversalLimitation.RECORD_LIMIT_REACHED -> TraversalLimitationDocument.RECORD_LIMIT_REACHED
        TraversalLimitation.BYTE_LIMIT_REACHED -> TraversalLimitationDocument.BYTE_LIMIT_REACHED
        TraversalLimitation.WORK_LIMIT_REACHED -> TraversalLimitationDocument.WORK_LIMIT_REACHED
        TraversalLimitation.TIME_LIMIT_REACHED -> TraversalLimitationDocument.TIME_LIMIT_REACHED
        TraversalLimitation.DEPTH_LIMIT_REACHED -> TraversalLimitationDocument.DEPTH_LIMIT_REACHED
        TraversalLimitation.FRONTIER_LIMIT_REACHED -> TraversalLimitationDocument.FRONTIER_LIMIT_REACHED
        TraversalLimitation.ONE_HOP_INCOMPLETE -> TraversalLimitationDocument.ONE_HOP_INCOMPLETE
        TraversalLimitation.NO_PROGRESS -> TraversalLimitationDocument.NO_PROGRESS
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun DomainRelationRejection.protocol(): RelationReadRejection =
    when (this) {
        DomainRelationRejection.WORKSPACE_NOT_READY -> RelationReadRejection.WORKSPACE_NOT_READY
        DomainRelationRejection.WORKSPACE_ROOT_MISMATCH -> RelationReadRejection.SELECTOR_WORKSPACE_MISMATCH
        DomainRelationRejection.STALE_GENERATION,
        DomainRelationRejection.STALE_SELECTOR -> RelationReadRejection.SELECTOR_STALE
        DomainRelationRejection.UNSUPPORTED_SUBJECT -> RelationReadRejection.RELATION_UNSUPPORTED
        DomainRelationRejection.SCOPE_REJECTED -> RelationReadRejection.SCOPE_REJECTED
        DomainRelationRejection.WORKSPACE_INDEX_UNAVAILABLE -> RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE
        DomainRelationRejection.OUTSIDE_SCOPE -> RelationReadRejection.OUTSIDE_SCOPE
        DomainRelationRejection.AMBIGUOUS_SUBJECT -> RelationReadRejection.AMBIGUOUS_SUBJECT
        DomainRelationRejection.COMPILER_IDENTITY_UNAVAILABLE -> RelationReadRejection.COMPILER_IDENTITY_UNAVAILABLE
        DomainRelationRejection.COMPILER_CONTRACT_VIOLATION -> RelationReadRejection.COMPILER_CONTRACT_VIOLATION
        DomainRelationRejection.CONTINUATION_CURSOR_MOVED -> RelationReadRejection.CONTINUATION_CURSOR_MOVED
    }

private fun DomainRelationResumeFailure.protocol(): RelationReadRejection =
    when (this) {
        DomainRelationResumeFailure.SUBJECT_MISMATCH -> RelationReadRejection.CONTINUATION_SUBJECT_MISMATCH
        DomainRelationResumeFailure.MEANING_MISMATCH,
        DomainRelationResumeFailure.PROVIDER_MISMATCH -> RelationReadRejection.CONTINUATION_RELATION_MISMATCH
        DomainRelationResumeFailure.SCOPE_MISMATCH -> RelationReadRejection.CONTINUATION_SCOPE_MISMATCH
        DomainRelationResumeFailure.GENERATION_MISMATCH -> RelationReadRejection.CONTINUATION_GENERATION_MISMATCH
    }

internal fun TraversalRejection.protocol(): TraversalRunRejection =
    when (this) {
        is TraversalRejection.OneHopRejected ->
            when (reason) {
                DomainRelationRejection.WORKSPACE_NOT_READY -> TraversalRunRejection.WORKSPACE_NOT_READY
                DomainRelationRejection.WORKSPACE_ROOT_MISMATCH -> TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH
                DomainRelationRejection.STALE_GENERATION,
                DomainRelationRejection.STALE_SELECTOR -> TraversalRunRejection.SELECTOR_STALE
                DomainRelationRejection.SCOPE_REJECTED -> TraversalRunRejection.SCOPE_REJECTED
                DomainRelationRejection.WORKSPACE_INDEX_UNAVAILABLE -> TraversalRunRejection.WORKSPACE_INDEX_UNAVAILABLE
                DomainRelationRejection.OUTSIDE_SCOPE -> TraversalRunRejection.OUTSIDE_SCOPE
                DomainRelationRejection.AMBIGUOUS_SUBJECT -> TraversalRunRejection.AMBIGUOUS_SUBJECT
                DomainRelationRejection.COMPILER_IDENTITY_UNAVAILABLE ->
                    TraversalRunRejection.COMPILER_IDENTITY_UNAVAILABLE
                DomainRelationRejection.COMPILER_CONTRACT_VIOLATION -> TraversalRunRejection.COMPILER_CONTRACT_VIOLATION
                DomainRelationRejection.CONTINUATION_CURSOR_MOVED -> TraversalRunRejection.CONTINUATION_CURSOR_MOVED
                DomainRelationRejection.UNSUPPORTED_SUBJECT -> TraversalRunRejection.RELATION_UNSUPPORTED
            }
        TraversalRejection.RequiredEvidenceUnavailable,
        TraversalRejection.RequiredEvidenceStale -> TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED
        TraversalRejection.ReaderContractViolation -> TraversalRunRejection.READER_CONTRACT_VIOLATION
        TraversalRejection.TraversalContractViolation -> TraversalRunRejection.TRAVERSAL_CONTRACT_VIOLATION
    }

private fun SelectorLookupRejection.relationProtocol(): RelationReadRejection =
    when (this) {
        SelectorLookupRejection.REVALIDATION_WRONG_KIND -> RelationReadRejection.REVALIDATION_WRONG_KIND
        SelectorLookupRejection.REVALIDATION_UNRETAINED -> RelationReadRejection.REVALIDATION_UNRETAINED
        SelectorLookupRejection.REVALIDATION_EXPIRED -> RelationReadRejection.REVALIDATION_EXPIRED
        SelectorLookupRejection.REVALIDATION_CAPACITY -> RelationReadRejection.REVALIDATION_CAPACITY
        SelectorLookupRejection.REVALIDATION_WORK_LIMIT_REACHED -> RelationReadRejection.REVALIDATION_WORK_LIMIT_REACHED
        SelectorLookupRejection.REVALIDATION_TIME_LIMIT_REACHED -> RelationReadRejection.REVALIDATION_TIME_LIMIT_REACHED
        SelectorLookupRejection.REVALIDATION_RETIRED -> RelationReadRejection.REVALIDATION_RETIRED
        SelectorLookupRejection.REVALIDATION_CAPTURE_UNAVAILABLE ->
            RelationReadRejection.REVALIDATION_CAPTURE_UNAVAILABLE
        SelectorLookupRejection.REVALIDATION_WORKSPACE_MISMATCH -> RelationReadRejection.REVALIDATION_WORKSPACE_MISMATCH
        SelectorLookupRejection.REVALIDATION_OWNER_MISMATCH -> RelationReadRejection.REVALIDATION_OWNER_MISMATCH
        SelectorLookupRejection.REVALIDATION_WORKSPACE_NOT_READY ->
            RelationReadRejection.REVALIDATION_WORKSPACE_NOT_READY
        SelectorLookupRejection.REVALIDATION_BASIS_MOVED -> RelationReadRejection.REVALIDATION_BASIS_MOVED
        SelectorLookupRejection.REVALIDATION_CONTENT_CHANGED -> RelationReadRejection.REVALIDATION_CONTENT_CHANGED
        SelectorLookupRejection.REVALIDATION_CONTENT_UNCOMMITTED ->
            RelationReadRejection.REVALIDATION_CONTENT_UNCOMMITTED
        SelectorLookupRejection.REVALIDATION_SCOPE_REJECTED -> RelationReadRejection.REVALIDATION_SCOPE_REJECTED
        SelectorLookupRejection.REVALIDATION_DECLARATION_MISSING ->
            RelationReadRejection.REVALIDATION_DECLARATION_MISSING
        SelectorLookupRejection.REVALIDATION_UNSUPPORTED_DECLARATION ->
            RelationReadRejection.REVALIDATION_UNSUPPORTED_DECLARATION
        SelectorLookupRejection.REVALIDATION_AMBIGUOUS -> RelationReadRejection.REVALIDATION_AMBIGUOUS
        SelectorLookupRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            RelationReadRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED
        SelectorLookupRejection.REVALIDATION_COMPILER_UNAVAILABLE ->
            RelationReadRejection.REVALIDATION_COMPILER_UNAVAILABLE

        SelectorLookupRejection.WRONG_KIND -> RelationReadRejection.SELECTOR_WRONG_KIND
        SelectorLookupRejection.MALFORMED -> RelationReadRejection.SELECTOR_MALFORMED
        SelectorLookupRejection.STALE -> RelationReadRejection.SELECTOR_STALE
        SelectorLookupRejection.WORKSPACE_MISMATCH -> RelationReadRejection.SELECTOR_WORKSPACE_MISMATCH
    }

internal fun SelectorLookupRejection.traversalProtocol(): TraversalRunRejection =
    when (this) {
        SelectorLookupRejection.REVALIDATION_WRONG_KIND -> TraversalRunRejection.REVALIDATION_WRONG_KIND
        SelectorLookupRejection.REVALIDATION_UNRETAINED -> TraversalRunRejection.REVALIDATION_UNRETAINED
        SelectorLookupRejection.REVALIDATION_EXPIRED -> TraversalRunRejection.REVALIDATION_EXPIRED
        SelectorLookupRejection.REVALIDATION_CAPACITY -> TraversalRunRejection.REVALIDATION_CAPACITY
        SelectorLookupRejection.REVALIDATION_WORK_LIMIT_REACHED -> TraversalRunRejection.REVALIDATION_WORK_LIMIT_REACHED
        SelectorLookupRejection.REVALIDATION_TIME_LIMIT_REACHED -> TraversalRunRejection.REVALIDATION_TIME_LIMIT_REACHED
        SelectorLookupRejection.REVALIDATION_RETIRED -> TraversalRunRejection.REVALIDATION_RETIRED
        SelectorLookupRejection.REVALIDATION_CAPTURE_UNAVAILABLE ->
            TraversalRunRejection.REVALIDATION_CAPTURE_UNAVAILABLE
        SelectorLookupRejection.REVALIDATION_WORKSPACE_MISMATCH -> TraversalRunRejection.REVALIDATION_WORKSPACE_MISMATCH
        SelectorLookupRejection.REVALIDATION_OWNER_MISMATCH -> TraversalRunRejection.REVALIDATION_OWNER_MISMATCH
        SelectorLookupRejection.REVALIDATION_WORKSPACE_NOT_READY ->
            TraversalRunRejection.REVALIDATION_WORKSPACE_NOT_READY
        SelectorLookupRejection.REVALIDATION_BASIS_MOVED -> TraversalRunRejection.REVALIDATION_BASIS_MOVED
        SelectorLookupRejection.REVALIDATION_CONTENT_CHANGED -> TraversalRunRejection.REVALIDATION_CONTENT_CHANGED
        SelectorLookupRejection.REVALIDATION_CONTENT_UNCOMMITTED ->
            TraversalRunRejection.REVALIDATION_CONTENT_UNCOMMITTED
        SelectorLookupRejection.REVALIDATION_SCOPE_REJECTED -> TraversalRunRejection.REVALIDATION_SCOPE_REJECTED
        SelectorLookupRejection.REVALIDATION_DECLARATION_MISSING ->
            TraversalRunRejection.REVALIDATION_DECLARATION_MISSING
        SelectorLookupRejection.REVALIDATION_UNSUPPORTED_DECLARATION ->
            TraversalRunRejection.REVALIDATION_UNSUPPORTED_DECLARATION
        SelectorLookupRejection.REVALIDATION_AMBIGUOUS -> TraversalRunRejection.REVALIDATION_AMBIGUOUS
        SelectorLookupRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            TraversalRunRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED
        SelectorLookupRejection.REVALIDATION_COMPILER_UNAVAILABLE ->
            TraversalRunRejection.REVALIDATION_COMPILER_UNAVAILABLE

        SelectorLookupRejection.WRONG_KIND -> TraversalRunRejection.SELECTOR_WRONG_KIND
        SelectorLookupRejection.MALFORMED -> TraversalRunRejection.SELECTOR_MALFORMED
        SelectorLookupRejection.STALE -> TraversalRunRejection.SELECTOR_STALE
        SelectorLookupRejection.WORKSPACE_MISMATCH -> TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH
    }

internal fun TraversalPlanResumeFailure.protocol(): TraversalRunRejection =
    when (this) {
        is TraversalPlanResumeFailure.Plan -> TraversalRunRejection.PLAN_REJECTED
        is TraversalPlanResumeFailure.Resume ->
            when (failure) {
                TraversalResumeFailure.SUBJECT_MISMATCH -> TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH
                TraversalResumeFailure.MEANING_MISMATCH -> TraversalRunRejection.CONTINUATION_RELATION_MISMATCH
                TraversalResumeFailure.SCOPE_MISMATCH -> TraversalRunRejection.CONTINUATION_SCOPE_MISMATCH
                TraversalResumeFailure.GENERATION_MISMATCH -> TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH
                TraversalResumeFailure.IDENTITY_MISMATCH -> TraversalRunRejection.CONTINUATION_MALFORMED
            }
    }
