package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection

internal fun TraversalRunQualification.toWireDocument(): TraversalRunQualificationWireDocument =
    when (this) {
        is TraversalRunQualification.Resumable ->
            TraversalRunQualificationWireDocument.Resumable(
                limitations = limitations.map(TraversalLimitationDocument::toWireDocument),
                relationLimitations = relationLimitations.map(RelationLimitationDocument::toWireDocument),
                continuation = continuation.value,
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
            TraversalContinuationDocument.parse(continuation).toWireDocumentConversion().flatMapConverted {
                admittedContinuation ->
                TraversalRunQualification.resumable(
                        limitations.map(TraversalLimitationWireDocument::toContract),
                        relationLimitations.map(RelationLimitationWireDocument::toContract),
                        admittedContinuation,
                    )
                    .toWireDocumentConversion()
            }
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

internal fun TraversalRunRejection.toWireDocument(): TraversalRunRejectionWireDocument =
    when (this) {
        TraversalRunRejection.WORKSPACE_NOT_READY -> TraversalRunRejectionWireDocument.WORKSPACE_NOT_READY
        TraversalRunRejection.SELECTOR_WRONG_KIND -> TraversalRunRejectionWireDocument.SELECTOR_WRONG_KIND
        TraversalRunRejection.SELECTOR_MALFORMED -> TraversalRunRejectionWireDocument.SELECTOR_MALFORMED
        TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH ->
            TraversalRunRejectionWireDocument.SELECTOR_WORKSPACE_MISMATCH
        TraversalRunRejection.SELECTOR_STALE -> TraversalRunRejectionWireDocument.SELECTOR_STALE
        TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED -> TraversalRunRejectionWireDocument.TOPOLOGY_BUILD_REQUIRED
        TraversalRunRejection.PLAN_REJECTED -> TraversalRunRejectionWireDocument.PLAN_REJECTED
        TraversalRunRejection.CONTINUATION_MALFORMED -> TraversalRunRejectionWireDocument.CONTINUATION_MALFORMED
        TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH ->
            TraversalRunRejectionWireDocument.CONTINUATION_SUBJECT_MISMATCH
        TraversalRunRejection.CONTINUATION_RELATION_MISMATCH ->
            TraversalRunRejectionWireDocument.CONTINUATION_RELATION_MISMATCH
        TraversalRunRejection.CONTINUATION_SCOPE_MISMATCH ->
            TraversalRunRejectionWireDocument.CONTINUATION_SCOPE_MISMATCH
        TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH ->
            TraversalRunRejectionWireDocument.CONTINUATION_GENERATION_MISMATCH
    }

internal fun TraversalRunRejectionWireDocument.toContract(): TraversalRunRejection =
    when (this) {
        TraversalRunRejectionWireDocument.WORKSPACE_NOT_READY -> TraversalRunRejection.WORKSPACE_NOT_READY
        TraversalRunRejectionWireDocument.SELECTOR_WRONG_KIND -> TraversalRunRejection.SELECTOR_WRONG_KIND
        TraversalRunRejectionWireDocument.SELECTOR_MALFORMED -> TraversalRunRejection.SELECTOR_MALFORMED
        TraversalRunRejectionWireDocument.SELECTOR_WORKSPACE_MISMATCH ->
            TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH
        TraversalRunRejectionWireDocument.SELECTOR_STALE -> TraversalRunRejection.SELECTOR_STALE
        TraversalRunRejectionWireDocument.TOPOLOGY_BUILD_REQUIRED -> TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED
        TraversalRunRejectionWireDocument.PLAN_REJECTED -> TraversalRunRejection.PLAN_REJECTED
        TraversalRunRejectionWireDocument.CONTINUATION_MALFORMED -> TraversalRunRejection.CONTINUATION_MALFORMED
        TraversalRunRejectionWireDocument.CONTINUATION_SUBJECT_MISMATCH ->
            TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH
        TraversalRunRejectionWireDocument.CONTINUATION_RELATION_MISMATCH ->
            TraversalRunRejection.CONTINUATION_RELATION_MISMATCH
        TraversalRunRejectionWireDocument.CONTINUATION_SCOPE_MISMATCH ->
            TraversalRunRejection.CONTINUATION_SCOPE_MISMATCH
        TraversalRunRejectionWireDocument.CONTINUATION_GENERATION_MISMATCH ->
            TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH
    }
