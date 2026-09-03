package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
internal fun SymbolDiscoverLimitation.toWireDocument():
    SymbolDiscoverLimitationWireDocument = when (this) {
    SymbolDiscoverLimitation.RESULT_LIMIT -> SymbolDiscoverLimitationWireDocument.RESULT_LIMIT
    SymbolDiscoverLimitation.BYTE_LIMIT -> SymbolDiscoverLimitationWireDocument.BYTE_LIMIT
    SymbolDiscoverLimitation.WORK_LIMIT -> SymbolDiscoverLimitationWireDocument.WORK_LIMIT
    SymbolDiscoverLimitation.TIME_LIMIT -> SymbolDiscoverLimitationWireDocument.TIME_LIMIT
    SymbolDiscoverLimitation.DUMB_MODE_TRANSITION ->
        SymbolDiscoverLimitationWireDocument.DUMB_MODE_TRANSITION
    SymbolDiscoverLimitation.PROVIDER_FAILURE ->
        SymbolDiscoverLimitationWireDocument.PROVIDER_FAILURE
    SymbolDiscoverLimitation.UNSCOPED_PROVIDER ->
        SymbolDiscoverLimitationWireDocument.UNSCOPED_PROVIDER
    SymbolDiscoverLimitation.UNSUPPORTED_ITEM ->
        SymbolDiscoverLimitationWireDocument.UNSUPPORTED_ITEM
    SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE ->
        SymbolDiscoverLimitationWireDocument.EXACT_DEFINITION_UNAVAILABLE
}

internal fun SymbolDiscoverLimitationWireDocument.toContract(): SymbolDiscoverLimitation =
    when (this) {
        SymbolDiscoverLimitationWireDocument.RESULT_LIMIT -> SymbolDiscoverLimitation.RESULT_LIMIT
        SymbolDiscoverLimitationWireDocument.BYTE_LIMIT -> SymbolDiscoverLimitation.BYTE_LIMIT
        SymbolDiscoverLimitationWireDocument.WORK_LIMIT -> SymbolDiscoverLimitation.WORK_LIMIT
        SymbolDiscoverLimitationWireDocument.TIME_LIMIT -> SymbolDiscoverLimitation.TIME_LIMIT
        SymbolDiscoverLimitationWireDocument.DUMB_MODE_TRANSITION ->
            SymbolDiscoverLimitation.DUMB_MODE_TRANSITION
        SymbolDiscoverLimitationWireDocument.PROVIDER_FAILURE ->
            SymbolDiscoverLimitation.PROVIDER_FAILURE
        SymbolDiscoverLimitationWireDocument.UNSCOPED_PROVIDER ->
            SymbolDiscoverLimitation.UNSCOPED_PROVIDER
        SymbolDiscoverLimitationWireDocument.UNSUPPORTED_ITEM ->
            SymbolDiscoverLimitation.UNSUPPORTED_ITEM
        SymbolDiscoverLimitationWireDocument.EXACT_DEFINITION_UNAVAILABLE ->
            SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE
    }

internal fun SymbolDiscoverRejection.toWireDocument(): SymbolDiscoverRejectionWireDocument =
    when (this) {
        SymbolDiscoverRejection.WORKSPACE_NOT_READY ->
            SymbolDiscoverRejectionWireDocument.WORKSPACE_NOT_READY
        SymbolDiscoverRejection.QUERY_REJECTED ->
            SymbolDiscoverRejectionWireDocument.QUERY_REJECTED
    }

internal fun SymbolDiscoverRejectionWireDocument.toContract(): SymbolDiscoverRejection =
    when (this) {
        SymbolDiscoverRejectionWireDocument.WORKSPACE_NOT_READY ->
            SymbolDiscoverRejection.WORKSPACE_NOT_READY
        SymbolDiscoverRejectionWireDocument.QUERY_REJECTED ->
            SymbolDiscoverRejection.QUERY_REJECTED
    }

internal fun SymbolInspectQualification.toWireDocument():
    SymbolInspectQualificationWireDocument = when (this) {
    SymbolInspectQualification.EVIDENCE_INCOMPLETE ->
        SymbolInspectQualificationWireDocument.EVIDENCE_INCOMPLETE
}

internal fun SymbolInspectQualificationWireDocument.toContract(): SymbolInspectQualification =
    when (this) {
        SymbolInspectQualificationWireDocument.EVIDENCE_INCOMPLETE ->
            SymbolInspectQualification.EVIDENCE_INCOMPLETE
    }

internal fun SymbolInspectRejection.toWireDocument(): SymbolInspectRejectionWireDocument =
    when (this) {
        SymbolInspectRejection.WORKSPACE_NOT_READY ->
            SymbolInspectRejectionWireDocument.WORKSPACE_NOT_READY
        SymbolInspectRejection.CANDIDATE_STALE ->
            SymbolInspectRejectionWireDocument.CANDIDATE_STALE
        SymbolInspectRejection.CANDIDATE_NOT_DECLARATION ->
            SymbolInspectRejectionWireDocument.CANDIDATE_NOT_DECLARATION
        SymbolInspectRejection.EXACT_SELECTOR_STALE ->
            SymbolInspectRejectionWireDocument.EXACT_SELECTOR_STALE
        SymbolInspectRejection.AMBIGUOUS -> SymbolInspectRejectionWireDocument.AMBIGUOUS
        SymbolInspectRejection.NOT_FOUND -> SymbolInspectRejectionWireDocument.NOT_FOUND
    }

internal fun SymbolInspectRejectionWireDocument.toContract(): SymbolInspectRejection =
    when (this) {
        SymbolInspectRejectionWireDocument.WORKSPACE_NOT_READY ->
            SymbolInspectRejection.WORKSPACE_NOT_READY
        SymbolInspectRejectionWireDocument.CANDIDATE_STALE ->
            SymbolInspectRejection.CANDIDATE_STALE
        SymbolInspectRejectionWireDocument.CANDIDATE_NOT_DECLARATION ->
            SymbolInspectRejection.CANDIDATE_NOT_DECLARATION
        SymbolInspectRejectionWireDocument.EXACT_SELECTOR_STALE ->
            SymbolInspectRejection.EXACT_SELECTOR_STALE
        SymbolInspectRejectionWireDocument.AMBIGUOUS -> SymbolInspectRejection.AMBIGUOUS
        SymbolInspectRejectionWireDocument.NOT_FOUND -> SymbolInspectRejection.NOT_FOUND
    }

internal fun RelationKindDocument.toWireDocument(): RelationKindWireDocument = when (this) {
    RelationKindDocument.REFERENCES -> RelationKindWireDocument.REFERENCES
    RelationKindDocument.CALLERS -> RelationKindWireDocument.CALLERS
    RelationKindDocument.CALLEES -> RelationKindWireDocument.CALLEES
    RelationKindDocument.IMPLEMENTATIONS -> RelationKindWireDocument.IMPLEMENTATIONS
    RelationKindDocument.INHERITORS -> RelationKindWireDocument.INHERITORS
    RelationKindDocument.OVERRIDES -> RelationKindWireDocument.OVERRIDES
    RelationKindDocument.TYPE_USES -> RelationKindWireDocument.TYPE_USES
}

internal fun RelationKindWireDocument.toContract(): RelationKindDocument = when (this) {
    RelationKindWireDocument.REFERENCES -> RelationKindDocument.REFERENCES
    RelationKindWireDocument.CALLERS -> RelationKindDocument.CALLERS
    RelationKindWireDocument.CALLEES -> RelationKindDocument.CALLEES
    RelationKindWireDocument.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
    RelationKindWireDocument.INHERITORS -> RelationKindDocument.INHERITORS
    RelationKindWireDocument.OVERRIDES -> RelationKindDocument.OVERRIDES
    RelationKindWireDocument.TYPE_USES -> RelationKindDocument.TYPE_USES
}

internal fun RelationReadQualification.toWireDocument():
    RelationReadQualificationWireDocument = when (this) {
    is RelationReadQualification.Resumable -> RelationReadQualificationWireDocument.Resumable(
        knownMinimum = knownMinimum.value,
        limitations = limitations.map(RelationLimitationDocument::toWireDocument),
        continuation = continuation.value,
    )
    is RelationReadQualification.TerminalIncomplete ->
        RelationReadQualificationWireDocument.TerminalIncomplete(
            knownMinimum = knownMinimum.value,
            limitations = limitations.map(RelationLimitationDocument::toWireDocument),
        )
}

internal fun RelationReadQualificationWireDocument.toContract():
    WireDocumentConversion<RelationReadQualification> = when (this) {
    is RelationReadQualificationWireDocument.Resumable ->
        RelationKnownMinimumDocument.parse(knownMinimum).toWireDocumentConversion()
            .flatMapConverted { admittedMinimum ->
                RelationContinuationDocument.parse(continuation).toWireDocumentConversion()
                    .flatMapConverted { admittedContinuation ->
                        RelationReadQualification.resumable(
                            admittedMinimum,
                            limitations.map(RelationLimitationWireDocument::toContract),
                            admittedContinuation,
                        ).toWireDocumentConversion()
                    }
            }
    is RelationReadQualificationWireDocument.TerminalIncomplete ->
        RelationKnownMinimumDocument.parse(knownMinimum).toWireDocumentConversion()
            .flatMapConverted { admittedMinimum ->
                RelationReadQualification.terminalIncomplete(
                    admittedMinimum,
                    limitations.map(RelationLimitationWireDocument::toContract),
                ).toWireDocumentConversion()
            }
}

private fun RelationLimitationDocument.toWireDocument(): RelationLimitationWireDocument =
    when (this) {
        RelationLimitationDocument.RESULT_LIMIT_REACHED ->
            RelationLimitationWireDocument.RESULT_LIMIT_REACHED
        RelationLimitationDocument.BYTE_LIMIT_REACHED ->
            RelationLimitationWireDocument.BYTE_LIMIT_REACHED
        RelationLimitationDocument.WORK_LIMIT_REACHED ->
            RelationLimitationWireDocument.WORK_LIMIT_REACHED
        RelationLimitationDocument.TIME_LIMIT_REACHED ->
            RelationLimitationWireDocument.TIME_LIMIT_REACHED
        RelationLimitationDocument.DUMB_MODE_TRANSITION ->
            RelationLimitationWireDocument.DUMB_MODE_TRANSITION
        RelationLimitationDocument.UNRESOLVED_TARGET ->
            RelationLimitationWireDocument.UNRESOLVED_TARGET
        RelationLimitationDocument.UNSUPPORTED_ITEM ->
            RelationLimitationWireDocument.UNSUPPORTED_ITEM
        RelationLimitationDocument.PROVIDER_FAILURE ->
            RelationLimitationWireDocument.PROVIDER_FAILURE
        RelationLimitationDocument.PROVIDER_INCOMPLETE ->
            RelationLimitationWireDocument.PROVIDER_INCOMPLETE
    }

private fun RelationLimitationWireDocument.toContract(): RelationLimitationDocument = when (this) {
    RelationLimitationWireDocument.RESULT_LIMIT_REACHED ->
        RelationLimitationDocument.RESULT_LIMIT_REACHED
    RelationLimitationWireDocument.BYTE_LIMIT_REACHED ->
        RelationLimitationDocument.BYTE_LIMIT_REACHED
    RelationLimitationWireDocument.WORK_LIMIT_REACHED ->
        RelationLimitationDocument.WORK_LIMIT_REACHED
    RelationLimitationWireDocument.TIME_LIMIT_REACHED ->
        RelationLimitationDocument.TIME_LIMIT_REACHED
    RelationLimitationWireDocument.DUMB_MODE_TRANSITION ->
        RelationLimitationDocument.DUMB_MODE_TRANSITION
    RelationLimitationWireDocument.UNRESOLVED_TARGET ->
        RelationLimitationDocument.UNRESOLVED_TARGET
    RelationLimitationWireDocument.UNSUPPORTED_ITEM ->
        RelationLimitationDocument.UNSUPPORTED_ITEM
    RelationLimitationWireDocument.PROVIDER_FAILURE ->
        RelationLimitationDocument.PROVIDER_FAILURE
    RelationLimitationWireDocument.PROVIDER_INCOMPLETE ->
        RelationLimitationDocument.PROVIDER_INCOMPLETE
}

internal fun RelationReadRejection.toWireDocument(): RelationReadRejectionWireDocument =
    when (this) {
        RelationReadRejection.WORKSPACE_NOT_READY ->
            RelationReadRejectionWireDocument.WORKSPACE_NOT_READY
        RelationReadRejection.SELECTOR_STALE -> RelationReadRejectionWireDocument.SELECTOR_STALE
        RelationReadRejection.RELATION_UNSUPPORTED ->
            RelationReadRejectionWireDocument.RELATION_UNSUPPORTED
        RelationReadRejection.CONTINUATION_MALFORMED ->
            RelationReadRejectionWireDocument.CONTINUATION_MALFORMED
        RelationReadRejection.CONTINUATION_SUBJECT_MISMATCH ->
            RelationReadRejectionWireDocument.CONTINUATION_SUBJECT_MISMATCH
        RelationReadRejection.CONTINUATION_RELATION_MISMATCH ->
            RelationReadRejectionWireDocument.CONTINUATION_RELATION_MISMATCH
        RelationReadRejection.CONTINUATION_SCOPE_MISMATCH ->
            RelationReadRejectionWireDocument.CONTINUATION_SCOPE_MISMATCH
        RelationReadRejection.CONTINUATION_GENERATION_MISMATCH ->
            RelationReadRejectionWireDocument.CONTINUATION_GENERATION_MISMATCH
        RelationReadRejection.CONTINUATION_CURSOR_MOVED ->
            RelationReadRejectionWireDocument.CONTINUATION_CURSOR_MOVED
    }

internal fun RelationReadRejectionWireDocument.toContract(): RelationReadRejection =
    when (this) {
        RelationReadRejectionWireDocument.WORKSPACE_NOT_READY ->
            RelationReadRejection.WORKSPACE_NOT_READY
        RelationReadRejectionWireDocument.SELECTOR_STALE -> RelationReadRejection.SELECTOR_STALE
        RelationReadRejectionWireDocument.RELATION_UNSUPPORTED ->
            RelationReadRejection.RELATION_UNSUPPORTED
        RelationReadRejectionWireDocument.CONTINUATION_MALFORMED ->
            RelationReadRejection.CONTINUATION_MALFORMED
        RelationReadRejectionWireDocument.CONTINUATION_SUBJECT_MISMATCH ->
            RelationReadRejection.CONTINUATION_SUBJECT_MISMATCH
        RelationReadRejectionWireDocument.CONTINUATION_RELATION_MISMATCH ->
            RelationReadRejection.CONTINUATION_RELATION_MISMATCH
        RelationReadRejectionWireDocument.CONTINUATION_SCOPE_MISMATCH ->
            RelationReadRejection.CONTINUATION_SCOPE_MISMATCH
        RelationReadRejectionWireDocument.CONTINUATION_GENERATION_MISMATCH ->
            RelationReadRejection.CONTINUATION_GENERATION_MISMATCH
        RelationReadRejectionWireDocument.CONTINUATION_CURSOR_MOVED ->
            RelationReadRejection.CONTINUATION_CURSOR_MOVED
    }

internal fun TraversalRunQualification.toWireDocument():
    TraversalRunQualificationWireDocument = when (this) {
    is TraversalRunQualification.Resumable -> TraversalRunQualificationWireDocument.Resumable(
        limitations = limitations.map(TraversalLimitationDocument::toWireDocument),
        relationLimitations = relationLimitations.map(RelationLimitationDocument::toWireDocument),
        continuation = continuation.value,
    )
    is TraversalRunQualification.TerminalIncomplete ->
        TraversalRunQualificationWireDocument.TerminalIncomplete(
            limitations = limitations.map(TraversalLimitationDocument::toWireDocument),
            relationLimitations = relationLimitations.map(
                RelationLimitationDocument::toWireDocument,
            ),
        )
}

internal fun TraversalRunQualificationWireDocument.toContract():
    WireDocumentConversion<TraversalRunQualification> = when (this) {
    is TraversalRunQualificationWireDocument.Resumable ->
        TraversalContinuationDocument.parse(continuation).toWireDocumentConversion()
            .flatMapConverted { admittedContinuation ->
                TraversalRunQualification.resumable(
                    limitations.map(TraversalLimitationWireDocument::toContract),
                    relationLimitations.map(RelationLimitationWireDocument::toContract),
                    admittedContinuation,
                ).toWireDocumentConversion()
            }
    is TraversalRunQualificationWireDocument.TerminalIncomplete ->
        TraversalRunQualification.terminalIncomplete(
            limitations.map(TraversalLimitationWireDocument::toContract),
            relationLimitations.map(RelationLimitationWireDocument::toContract),
        ).toWireDocumentConversion()
}

private fun TraversalLimitationDocument.toWireDocument(): TraversalLimitationWireDocument =
    when (this) {
        TraversalLimitationDocument.RECORD_LIMIT_REACHED ->
            TraversalLimitationWireDocument.RECORD_LIMIT_REACHED
        TraversalLimitationDocument.BYTE_LIMIT_REACHED ->
            TraversalLimitationWireDocument.BYTE_LIMIT_REACHED
        TraversalLimitationDocument.WORK_LIMIT_REACHED ->
            TraversalLimitationWireDocument.WORK_LIMIT_REACHED
        TraversalLimitationDocument.TIME_LIMIT_REACHED ->
            TraversalLimitationWireDocument.TIME_LIMIT_REACHED
        TraversalLimitationDocument.DEPTH_LIMIT_REACHED ->
            TraversalLimitationWireDocument.DEPTH_LIMIT_REACHED
        TraversalLimitationDocument.FRONTIER_LIMIT_REACHED ->
            TraversalLimitationWireDocument.FRONTIER_LIMIT_REACHED
        TraversalLimitationDocument.ONE_HOP_INCOMPLETE ->
            TraversalLimitationWireDocument.ONE_HOP_INCOMPLETE
    }

private fun TraversalLimitationWireDocument.toContract(): TraversalLimitationDocument =
    when (this) {
        TraversalLimitationWireDocument.RECORD_LIMIT_REACHED ->
            TraversalLimitationDocument.RECORD_LIMIT_REACHED
        TraversalLimitationWireDocument.BYTE_LIMIT_REACHED ->
            TraversalLimitationDocument.BYTE_LIMIT_REACHED
        TraversalLimitationWireDocument.WORK_LIMIT_REACHED ->
            TraversalLimitationDocument.WORK_LIMIT_REACHED
        TraversalLimitationWireDocument.TIME_LIMIT_REACHED ->
            TraversalLimitationDocument.TIME_LIMIT_REACHED
        TraversalLimitationWireDocument.DEPTH_LIMIT_REACHED ->
            TraversalLimitationDocument.DEPTH_LIMIT_REACHED
        TraversalLimitationWireDocument.FRONTIER_LIMIT_REACHED ->
            TraversalLimitationDocument.FRONTIER_LIMIT_REACHED
        TraversalLimitationWireDocument.ONE_HOP_INCOMPLETE ->
            TraversalLimitationDocument.ONE_HOP_INCOMPLETE
    }

internal fun TraversalRunRejection.toWireDocument(): TraversalRunRejectionWireDocument =
    when (this) {
        TraversalRunRejection.WORKSPACE_NOT_READY ->
            TraversalRunRejectionWireDocument.WORKSPACE_NOT_READY
        TraversalRunRejection.SELECTOR_STALE ->
            TraversalRunRejectionWireDocument.SELECTOR_STALE
        TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED ->
            TraversalRunRejectionWireDocument.TOPOLOGY_BUILD_REQUIRED
        TraversalRunRejection.PLAN_REJECTED -> TraversalRunRejectionWireDocument.PLAN_REJECTED
        TraversalRunRejection.CONTINUATION_MALFORMED ->
            TraversalRunRejectionWireDocument.CONTINUATION_MALFORMED
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
        TraversalRunRejectionWireDocument.WORKSPACE_NOT_READY ->
            TraversalRunRejection.WORKSPACE_NOT_READY
        TraversalRunRejectionWireDocument.SELECTOR_STALE -> TraversalRunRejection.SELECTOR_STALE
        TraversalRunRejectionWireDocument.TOPOLOGY_BUILD_REQUIRED ->
            TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED
        TraversalRunRejectionWireDocument.PLAN_REJECTED -> TraversalRunRejection.PLAN_REJECTED
        TraversalRunRejectionWireDocument.CONTINUATION_MALFORMED ->
            TraversalRunRejection.CONTINUATION_MALFORMED
        TraversalRunRejectionWireDocument.CONTINUATION_SUBJECT_MISMATCH ->
            TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH
        TraversalRunRejectionWireDocument.CONTINUATION_RELATION_MISMATCH ->
            TraversalRunRejection.CONTINUATION_RELATION_MISMATCH
        TraversalRunRejectionWireDocument.CONTINUATION_SCOPE_MISMATCH ->
            TraversalRunRejection.CONTINUATION_SCOPE_MISMATCH
        TraversalRunRejectionWireDocument.CONTINUATION_GENERATION_MISMATCH ->
            TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH
    }

internal fun DiagnosticCheckQualification.toWireDocument():
    DiagnosticCheckQualificationWireDocument = DiagnosticCheckQualificationWireDocument(
    knownDiagnosticCount = knownDiagnosticCount.value,
    resultLimitReached = resultLimitReached,
    analyzedFiles = analyzedFiles.map { it.value },
    limitations = limitations.map { limitation ->
        DiagnosticLimitationWireDocument(
            limitation.file.value,
            limitation.reason.toWireDocument(),
        )
    },
)

internal fun DiagnosticCheckQualificationWireDocument.toContract():
    WireDocumentConversion<DiagnosticCheckQualification> =
    DiagnosticKnownCountDocument.parse(knownDiagnosticCount).toWireDocumentConversion()
        .flatMapConverted { admittedCount ->
            combineConverted(
                analyzedFiles.convertEach { raw -> raw.protocolQualificationText() },
                limitations.convertEach(DiagnosticLimitationWireDocument::toContract),
            ) { admittedFiles, admittedLimitations ->
                DiagnosticCheckQualification.create(
                    admittedCount,
                    resultLimitReached,
                    admittedFiles,
                    admittedLimitations,
                ).toWireDocumentConversion()
            }.flattenConverted()
        }

private fun DiagnosticLimitationWireDocument.toContract():
    WireDocumentConversion<DiagnosticLimitationDocument> =
    file.protocolQualificationText().mapConverted {
    admittedFile -> DiagnosticLimitationDocument(admittedFile, reason.toContract())
}

private fun String.protocolQualificationText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()

private fun DiagnosticLimitationReasonDocument.toWireDocument():
    DiagnosticLimitationReasonWireDocument = when (this) {
    DiagnosticLimitationReasonDocument.FILE_UNAVAILABLE ->
        DiagnosticLimitationReasonWireDocument.FILE_UNAVAILABLE
    DiagnosticLimitationReasonDocument.OUTSIDE_SOURCE_CONTENT ->
        DiagnosticLimitationReasonWireDocument.OUTSIDE_SOURCE_CONTENT
    DiagnosticLimitationReasonDocument.INDEXING -> DiagnosticLimitationReasonWireDocument.INDEXING
    DiagnosticLimitationReasonDocument.PSI_UNAVAILABLE ->
        DiagnosticLimitationReasonWireDocument.PSI_UNAVAILABLE
    DiagnosticLimitationReasonDocument.UNSUPPORTED_FILE_KIND ->
        DiagnosticLimitationReasonWireDocument.UNSUPPORTED_FILE_KIND
    DiagnosticLimitationReasonDocument.UNSUPPORTED_DIAGNOSTIC ->
        DiagnosticLimitationReasonWireDocument.UNSUPPORTED_DIAGNOSTIC
    DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE ->
        DiagnosticLimitationReasonWireDocument.ANALYSIS_UNAVAILABLE
}

private fun DiagnosticLimitationReasonWireDocument.toContract():
    DiagnosticLimitationReasonDocument = when (this) {
    DiagnosticLimitationReasonWireDocument.FILE_UNAVAILABLE ->
        DiagnosticLimitationReasonDocument.FILE_UNAVAILABLE
    DiagnosticLimitationReasonWireDocument.OUTSIDE_SOURCE_CONTENT ->
        DiagnosticLimitationReasonDocument.OUTSIDE_SOURCE_CONTENT
    DiagnosticLimitationReasonWireDocument.INDEXING -> DiagnosticLimitationReasonDocument.INDEXING
    DiagnosticLimitationReasonWireDocument.PSI_UNAVAILABLE ->
        DiagnosticLimitationReasonDocument.PSI_UNAVAILABLE
    DiagnosticLimitationReasonWireDocument.UNSUPPORTED_FILE_KIND ->
        DiagnosticLimitationReasonDocument.UNSUPPORTED_FILE_KIND
    DiagnosticLimitationReasonWireDocument.UNSUPPORTED_DIAGNOSTIC ->
        DiagnosticLimitationReasonDocument.UNSUPPORTED_DIAGNOSTIC
    DiagnosticLimitationReasonWireDocument.ANALYSIS_UNAVAILABLE ->
        DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE
}

internal fun DiagnosticCheckRejection.toWireDocument(): DiagnosticCheckRejectionWireDocument =
    when (this) {
        DiagnosticCheckRejection.WORKSPACE_NOT_READY ->
            DiagnosticCheckRejectionWireDocument.WORKSPACE_NOT_READY
        DiagnosticCheckRejection.SCOPE_REJECTED ->
            DiagnosticCheckRejectionWireDocument.SCOPE_REJECTED
    }

internal fun DiagnosticCheckRejectionWireDocument.toContract(): DiagnosticCheckRejection =
    when (this) {
        DiagnosticCheckRejectionWireDocument.WORKSPACE_NOT_READY ->
            DiagnosticCheckRejection.WORKSPACE_NOT_READY
        DiagnosticCheckRejectionWireDocument.SCOPE_REJECTED ->
            DiagnosticCheckRejection.SCOPE_REJECTED
    }
