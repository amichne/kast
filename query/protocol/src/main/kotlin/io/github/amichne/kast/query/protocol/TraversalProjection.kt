package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadRejection as DomainRelationRejection
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPlanResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResumeFailure

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
