package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection as TraversalCause
import io.github.amichne.kast.protocol.wire.TraversalRunRejectionWireDocument as TraversalCode

internal fun TraversalRunQualification.toWireDocument(): TraversalRunQualificationWireDocument =
    when (this) {
        is TraversalRunQualification.Resumable ->
            TraversalRunQualificationWireDocument.Resumable(
                limitations = limitations.map(TraversalLimitationDocument::toWireDocument),
                relationLimitations = relationLimitations.map(RelationLimitationDocument::toWireDocument),
                checkpoint = checkpoint,
                nextAction = nextAction,
            )
        is TraversalRunQualification.TerminalIncomplete ->
            TraversalRunQualificationWireDocument.TerminalIncomplete(
                limitations = limitations.map(TraversalLimitationDocument::toWireDocument),
                relationLimitations = relationLimitations.map(RelationLimitationDocument::toWireDocument),
            )
    }

internal fun TraversalRunQualificationWireDocument.toContract(): WireDocumentConversion<TraversalRunQualification> =
    when (this) {
        is TraversalRunQualificationWireDocument.Resumable ->
            TraversalRunQualification.admitResumable(
                    limitations.map(TraversalLimitationWireDocument::toContract),
                    relationLimitations.map(RelationLimitationWireDocument::toContract),
                    checkpoint,
                    nextAction,
                )
                .toWireDocumentConversion()
        is TraversalRunQualificationWireDocument.TerminalIncomplete ->
            TraversalRunQualification.terminalIncomplete(
                    limitations.map(TraversalLimitationWireDocument::toContract),
                    relationLimitations.map(RelationLimitationWireDocument::toContract),
                )
                .toWireDocumentConversion()
    }

private fun TraversalLimitationDocument.toWireDocument(): TraversalLimitationWireDocument =
    when (this) {
        TraversalLimitationDocument.RECORD_LIMIT_REACHED -> TraversalLimitationWireDocument.RECORD_LIMIT_REACHED
        TraversalLimitationDocument.BYTE_LIMIT_REACHED -> TraversalLimitationWireDocument.BYTE_LIMIT_REACHED
        TraversalLimitationDocument.WORK_LIMIT_REACHED -> TraversalLimitationWireDocument.WORK_LIMIT_REACHED
        TraversalLimitationDocument.TIME_LIMIT_REACHED -> TraversalLimitationWireDocument.TIME_LIMIT_REACHED
        TraversalLimitationDocument.DEPTH_LIMIT_REACHED -> TraversalLimitationWireDocument.DEPTH_LIMIT_REACHED
        TraversalLimitationDocument.FRONTIER_LIMIT_REACHED -> TraversalLimitationWireDocument.FRONTIER_LIMIT_REACHED
        TraversalLimitationDocument.ONE_HOP_INCOMPLETE -> TraversalLimitationWireDocument.ONE_HOP_INCOMPLETE
        TraversalLimitationDocument.NO_PROGRESS -> TraversalLimitationWireDocument.NO_PROGRESS
    }

private fun TraversalLimitationWireDocument.toContract(): TraversalLimitationDocument =
    when (this) {
        TraversalLimitationWireDocument.RECORD_LIMIT_REACHED -> TraversalLimitationDocument.RECORD_LIMIT_REACHED
        TraversalLimitationWireDocument.BYTE_LIMIT_REACHED -> TraversalLimitationDocument.BYTE_LIMIT_REACHED
        TraversalLimitationWireDocument.WORK_LIMIT_REACHED -> TraversalLimitationDocument.WORK_LIMIT_REACHED
        TraversalLimitationWireDocument.TIME_LIMIT_REACHED -> TraversalLimitationDocument.TIME_LIMIT_REACHED
        TraversalLimitationWireDocument.DEPTH_LIMIT_REACHED -> TraversalLimitationDocument.DEPTH_LIMIT_REACHED
        TraversalLimitationWireDocument.FRONTIER_LIMIT_REACHED -> TraversalLimitationDocument.FRONTIER_LIMIT_REACHED
        TraversalLimitationWireDocument.ONE_HOP_INCOMPLETE -> TraversalLimitationDocument.ONE_HOP_INCOMPLETE
        TraversalLimitationWireDocument.NO_PROGRESS -> TraversalLimitationDocument.NO_PROGRESS
    }

internal fun TraversalCause.toWireDocument(): TraversalCode =
    when (this) {
        TraversalCause.REVALIDATION_WRONG_KIND -> TraversalCode.REVALIDATION_WRONG_KIND
        TraversalCause.REVALIDATION_UNRETAINED -> TraversalCode.REVALIDATION_UNRETAINED
        TraversalCause.REVALIDATION_EXPIRED -> TraversalCode.REVALIDATION_EXPIRED
        TraversalCause.REVALIDATION_CAPACITY -> TraversalCode.REVALIDATION_CAPACITY
        TraversalCause.REVALIDATION_WORK_LIMIT_REACHED -> TraversalCode.REVALIDATION_WORK_LIMIT_REACHED
        TraversalCause.REVALIDATION_TIME_LIMIT_REACHED -> TraversalCode.REVALIDATION_TIME_LIMIT_REACHED
        TraversalCause.REVALIDATION_RETIRED -> TraversalCode.REVALIDATION_RETIRED
        TraversalCause.REVALIDATION_CAPTURE_UNAVAILABLE -> TraversalCode.REVALIDATION_CAPTURE_UNAVAILABLE
        TraversalCause.REVALIDATION_WORKSPACE_MISMATCH -> TraversalCode.REVALIDATION_WORKSPACE_MISMATCH
        TraversalCause.REVALIDATION_OWNER_MISMATCH -> TraversalCode.REVALIDATION_OWNER_MISMATCH
        TraversalCause.REVALIDATION_WORKSPACE_NOT_READY -> TraversalCode.REVALIDATION_WORKSPACE_NOT_READY
        TraversalCause.REVALIDATION_BASIS_MOVED -> TraversalCode.REVALIDATION_BASIS_MOVED
        TraversalCause.REVALIDATION_CONTENT_CHANGED -> TraversalCode.REVALIDATION_CONTENT_CHANGED
        TraversalCause.REVALIDATION_CONTENT_UNCOMMITTED -> TraversalCode.REVALIDATION_CONTENT_UNCOMMITTED
        TraversalCause.REVALIDATION_SCOPE_REJECTED -> TraversalCode.REVALIDATION_SCOPE_REJECTED
        TraversalCause.REVALIDATION_DECLARATION_MISSING -> TraversalCode.REVALIDATION_DECLARATION_MISSING
        TraversalCause.REVALIDATION_UNSUPPORTED_DECLARATION -> TraversalCode.REVALIDATION_UNSUPPORTED_DECLARATION
        TraversalCause.REVALIDATION_AMBIGUOUS -> TraversalCode.REVALIDATION_AMBIGUOUS
        TraversalCause.REVALIDATION_COMPILER_IDENTITY_CHANGED -> TraversalCode.REVALIDATION_COMPILER_IDENTITY_CHANGED
        TraversalCause.REVALIDATION_COMPILER_UNAVAILABLE -> TraversalCode.REVALIDATION_COMPILER_UNAVAILABLE

        TraversalCause.SCOPE_REJECTED -> TraversalCode.SCOPE_REJECTED
        TraversalCause.WORKSPACE_INDEX_UNAVAILABLE -> TraversalCode.WORKSPACE_INDEX_UNAVAILABLE
        TraversalCause.OUTSIDE_SCOPE -> TraversalCode.OUTSIDE_SCOPE
        TraversalCause.AMBIGUOUS_SUBJECT -> TraversalCode.AMBIGUOUS_SUBJECT
        TraversalCause.COMPILER_IDENTITY_UNAVAILABLE -> TraversalCode.COMPILER_IDENTITY_UNAVAILABLE
        TraversalCause.COMPILER_CONTRACT_VIOLATION -> TraversalCode.COMPILER_CONTRACT_VIOLATION
        TraversalCause.CONTINUATION_CURSOR_MOVED -> TraversalCode.CONTINUATION_CURSOR_MOVED
        TraversalCause.RELATION_UNSUPPORTED -> TraversalCode.RELATION_UNSUPPORTED
        TraversalCause.READER_CONTRACT_VIOLATION -> TraversalCode.READER_CONTRACT_VIOLATION
        TraversalCause.TRAVERSAL_CONTRACT_VIOLATION -> TraversalCode.TRAVERSAL_CONTRACT_VIOLATION
        TraversalCause.DEPTH_LIMIT_EXCEEDED -> TraversalCode.DEPTH_LIMIT_EXCEEDED

        TraversalCause.CONTINUATION_UNAVAILABLE -> TraversalCode.CONTINUATION_UNAVAILABLE
        TraversalCause.CONTINUATION_REQUEST_MISMATCH -> TraversalCode.CONTINUATION_REQUEST_MISMATCH
        TraversalCause.WORKSPACE_NOT_READY -> TraversalCode.WORKSPACE_NOT_READY
        TraversalCause.SELECTOR_WRONG_KIND -> TraversalCode.SELECTOR_WRONG_KIND
        TraversalCause.SELECTOR_MALFORMED -> TraversalCode.SELECTOR_MALFORMED
        TraversalCause.SELECTOR_WORKSPACE_MISMATCH -> TraversalCode.SELECTOR_WORKSPACE_MISMATCH
        TraversalCause.SELECTOR_STALE -> TraversalCode.SELECTOR_STALE
        TraversalCause.TOPOLOGY_BUILD_REQUIRED -> TraversalCode.TOPOLOGY_BUILD_REQUIRED
        TraversalCause.PLAN_REJECTED -> TraversalCode.PLAN_REJECTED
        TraversalCause.CONTINUATION_MALFORMED -> TraversalCode.CONTINUATION_MALFORMED
        TraversalCause.CONTINUATION_SUBJECT_MISMATCH -> TraversalCode.CONTINUATION_SUBJECT_MISMATCH
        TraversalCause.CONTINUATION_RELATION_MISMATCH -> TraversalCode.CONTINUATION_RELATION_MISMATCH
        TraversalCause.CONTINUATION_SCOPE_MISMATCH -> TraversalCode.CONTINUATION_SCOPE_MISMATCH
        TraversalCause.CONTINUATION_GENERATION_MISMATCH -> TraversalCode.CONTINUATION_GENERATION_MISMATCH
    }

internal fun TraversalCode.toContract(): TraversalCause =
    when (this) {
        TraversalCode.REVALIDATION_WRONG_KIND -> TraversalCause.REVALIDATION_WRONG_KIND
        TraversalCode.REVALIDATION_UNRETAINED -> TraversalCause.REVALIDATION_UNRETAINED
        TraversalCode.REVALIDATION_EXPIRED -> TraversalCause.REVALIDATION_EXPIRED
        TraversalCode.REVALIDATION_CAPACITY -> TraversalCause.REVALIDATION_CAPACITY
        TraversalCode.REVALIDATION_WORK_LIMIT_REACHED -> TraversalCause.REVALIDATION_WORK_LIMIT_REACHED
        TraversalCode.REVALIDATION_TIME_LIMIT_REACHED -> TraversalCause.REVALIDATION_TIME_LIMIT_REACHED
        TraversalCode.REVALIDATION_RETIRED -> TraversalCause.REVALIDATION_RETIRED
        TraversalCode.REVALIDATION_CAPTURE_UNAVAILABLE -> TraversalCause.REVALIDATION_CAPTURE_UNAVAILABLE
        TraversalCode.REVALIDATION_WORKSPACE_MISMATCH -> TraversalCause.REVALIDATION_WORKSPACE_MISMATCH
        TraversalCode.REVALIDATION_OWNER_MISMATCH -> TraversalCause.REVALIDATION_OWNER_MISMATCH
        TraversalCode.REVALIDATION_WORKSPACE_NOT_READY -> TraversalCause.REVALIDATION_WORKSPACE_NOT_READY
        TraversalCode.REVALIDATION_BASIS_MOVED -> TraversalCause.REVALIDATION_BASIS_MOVED
        TraversalCode.REVALIDATION_CONTENT_CHANGED -> TraversalCause.REVALIDATION_CONTENT_CHANGED
        TraversalCode.REVALIDATION_CONTENT_UNCOMMITTED -> TraversalCause.REVALIDATION_CONTENT_UNCOMMITTED
        TraversalCode.REVALIDATION_SCOPE_REJECTED -> TraversalCause.REVALIDATION_SCOPE_REJECTED
        TraversalCode.REVALIDATION_DECLARATION_MISSING -> TraversalCause.REVALIDATION_DECLARATION_MISSING
        TraversalCode.REVALIDATION_UNSUPPORTED_DECLARATION -> TraversalCause.REVALIDATION_UNSUPPORTED_DECLARATION
        TraversalCode.REVALIDATION_AMBIGUOUS -> TraversalCause.REVALIDATION_AMBIGUOUS
        TraversalCode.REVALIDATION_COMPILER_IDENTITY_CHANGED -> TraversalCause.REVALIDATION_COMPILER_IDENTITY_CHANGED
        TraversalCode.REVALIDATION_COMPILER_UNAVAILABLE -> TraversalCause.REVALIDATION_COMPILER_UNAVAILABLE

        TraversalCode.SCOPE_REJECTED -> TraversalCause.SCOPE_REJECTED
        TraversalCode.WORKSPACE_INDEX_UNAVAILABLE -> TraversalCause.WORKSPACE_INDEX_UNAVAILABLE
        TraversalCode.OUTSIDE_SCOPE -> TraversalCause.OUTSIDE_SCOPE
        TraversalCode.AMBIGUOUS_SUBJECT -> TraversalCause.AMBIGUOUS_SUBJECT
        TraversalCode.COMPILER_IDENTITY_UNAVAILABLE -> TraversalCause.COMPILER_IDENTITY_UNAVAILABLE
        TraversalCode.COMPILER_CONTRACT_VIOLATION -> TraversalCause.COMPILER_CONTRACT_VIOLATION
        TraversalCode.CONTINUATION_CURSOR_MOVED -> TraversalCause.CONTINUATION_CURSOR_MOVED
        TraversalCode.RELATION_UNSUPPORTED -> TraversalCause.RELATION_UNSUPPORTED
        TraversalCode.READER_CONTRACT_VIOLATION -> TraversalCause.READER_CONTRACT_VIOLATION
        TraversalCode.TRAVERSAL_CONTRACT_VIOLATION -> TraversalCause.TRAVERSAL_CONTRACT_VIOLATION
        TraversalCode.DEPTH_LIMIT_EXCEEDED -> TraversalCause.DEPTH_LIMIT_EXCEEDED

        TraversalCode.CONTINUATION_UNAVAILABLE -> TraversalCause.CONTINUATION_UNAVAILABLE
        TraversalCode.CONTINUATION_REQUEST_MISMATCH -> TraversalCause.CONTINUATION_REQUEST_MISMATCH
        TraversalCode.WORKSPACE_NOT_READY -> TraversalCause.WORKSPACE_NOT_READY
        TraversalCode.SELECTOR_WRONG_KIND -> TraversalCause.SELECTOR_WRONG_KIND
        TraversalCode.SELECTOR_MALFORMED -> TraversalCause.SELECTOR_MALFORMED
        TraversalCode.SELECTOR_WORKSPACE_MISMATCH -> TraversalCause.SELECTOR_WORKSPACE_MISMATCH
        TraversalCode.SELECTOR_STALE -> TraversalCause.SELECTOR_STALE
        TraversalCode.TOPOLOGY_BUILD_REQUIRED -> TraversalCause.TOPOLOGY_BUILD_REQUIRED
        TraversalCode.PLAN_REJECTED -> TraversalCause.PLAN_REJECTED
        TraversalCode.CONTINUATION_MALFORMED -> TraversalCause.CONTINUATION_MALFORMED
        TraversalCode.CONTINUATION_SUBJECT_MISMATCH -> TraversalCause.CONTINUATION_SUBJECT_MISMATCH
        TraversalCode.CONTINUATION_RELATION_MISMATCH -> TraversalCause.CONTINUATION_RELATION_MISMATCH
        TraversalCode.CONTINUATION_SCOPE_MISMATCH -> TraversalCause.CONTINUATION_SCOPE_MISMATCH
        TraversalCode.CONTINUATION_GENERATION_MISMATCH -> TraversalCause.CONTINUATION_GENERATION_MISMATCH
    }
