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
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument

internal fun WorkspaceStateDocument.toWireDocument(): WorkspaceStateWireDocument = when (this) {
    WorkspaceStateDocument.ABSENT -> WorkspaceStateWireDocument.ABSENT
    WorkspaceStateDocument.STARTING -> WorkspaceStateWireDocument.STARTING
    WorkspaceStateDocument.RECONCILING -> WorkspaceStateWireDocument.RECONCILING
    WorkspaceStateDocument.READY -> WorkspaceStateWireDocument.READY
    WorkspaceStateDocument.BLOCKED -> WorkspaceStateWireDocument.BLOCKED
    WorkspaceStateDocument.STOPPING -> WorkspaceStateWireDocument.STOPPING
}

internal fun WorkspaceStateWireDocument.toContract(): WorkspaceStateDocument = when (this) {
    WorkspaceStateWireDocument.ABSENT -> WorkspaceStateDocument.ABSENT
    WorkspaceStateWireDocument.STARTING -> WorkspaceStateDocument.STARTING
    WorkspaceStateWireDocument.RECONCILING -> WorkspaceStateDocument.RECONCILING
    WorkspaceStateWireDocument.READY -> WorkspaceStateDocument.READY
    WorkspaceStateWireDocument.BLOCKED -> WorkspaceStateDocument.BLOCKED
    WorkspaceStateWireDocument.STOPPING -> WorkspaceStateDocument.STOPPING
}

internal fun WorkspaceInspectQualification.toWireDocument():
    WorkspaceInspectQualificationWireDocument = when (this) {
    WorkspaceInspectQualification.RECONCILING ->
        WorkspaceInspectQualificationWireDocument.RECONCILING
}

internal fun WorkspaceInspectQualificationWireDocument.toContract():
    WorkspaceInspectQualification = when (this) {
    WorkspaceInspectQualificationWireDocument.RECONCILING ->
        WorkspaceInspectQualification.RECONCILING
}

internal fun WorkspaceInspectRejection.toWireDocument():
    WorkspaceInspectRejectionWireDocument = when (this) {
    WorkspaceInspectRejection.ROOT_UNAVAILABLE ->
        WorkspaceInspectRejectionWireDocument.ROOT_UNAVAILABLE
    WorkspaceInspectRejection.RUNTIME_BLOCKED ->
        WorkspaceInspectRejectionWireDocument.RUNTIME_BLOCKED
}

internal fun WorkspaceInspectRejectionWireDocument.toContract(): WorkspaceInspectRejection =
    when (this) {
        WorkspaceInspectRejectionWireDocument.ROOT_UNAVAILABLE ->
            WorkspaceInspectRejection.ROOT_UNAVAILABLE
        WorkspaceInspectRejectionWireDocument.RUNTIME_BLOCKED ->
            WorkspaceInspectRejection.RUNTIME_BLOCKED
    }

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

internal fun SymbolResolveQualification.toWireDocument():
    SymbolResolveQualificationWireDocument = when (this) {
    SymbolResolveQualification.EVIDENCE_INCOMPLETE ->
        SymbolResolveQualificationWireDocument.EVIDENCE_INCOMPLETE
}

internal fun SymbolResolveQualificationWireDocument.toContract(): SymbolResolveQualification =
    when (this) {
        SymbolResolveQualificationWireDocument.EVIDENCE_INCOMPLETE ->
            SymbolResolveQualification.EVIDENCE_INCOMPLETE
    }

internal fun SymbolResolveRejection.toWireDocument(): SymbolResolveRejectionWireDocument =
    when (this) {
        SymbolResolveRejection.WORKSPACE_NOT_READY ->
            SymbolResolveRejectionWireDocument.WORKSPACE_NOT_READY
        SymbolResolveRejection.CANDIDATE_STALE ->
            SymbolResolveRejectionWireDocument.CANDIDATE_STALE
        SymbolResolveRejection.AMBIGUOUS -> SymbolResolveRejectionWireDocument.AMBIGUOUS
        SymbolResolveRejection.NOT_FOUND -> SymbolResolveRejectionWireDocument.NOT_FOUND
    }

internal fun SymbolResolveRejectionWireDocument.toContract(): SymbolResolveRejection =
    when (this) {
        SymbolResolveRejectionWireDocument.WORKSPACE_NOT_READY ->
            SymbolResolveRejection.WORKSPACE_NOT_READY
        SymbolResolveRejectionWireDocument.CANDIDATE_STALE ->
            SymbolResolveRejection.CANDIDATE_STALE
        SymbolResolveRejectionWireDocument.AMBIGUOUS -> SymbolResolveRejection.AMBIGUOUS
        SymbolResolveRejectionWireDocument.NOT_FOUND -> SymbolResolveRejection.NOT_FOUND
    }

internal fun SymbolDescribeQualification.toWireDocument():
    SymbolDescribeQualificationWireDocument = when (this) {
    SymbolDescribeQualification.EVIDENCE_INCOMPLETE ->
        SymbolDescribeQualificationWireDocument.EVIDENCE_INCOMPLETE
}

internal fun SymbolDescribeQualificationWireDocument.toContract(): SymbolDescribeQualification =
    when (this) {
        SymbolDescribeQualificationWireDocument.EVIDENCE_INCOMPLETE ->
            SymbolDescribeQualification.EVIDENCE_INCOMPLETE
    }

internal fun SymbolDescribeRejection.toWireDocument(): SymbolDescribeRejectionWireDocument =
    when (this) {
        SymbolDescribeRejection.WORKSPACE_NOT_READY ->
            SymbolDescribeRejectionWireDocument.WORKSPACE_NOT_READY
        SymbolDescribeRejection.SELECTOR_STALE ->
            SymbolDescribeRejectionWireDocument.SELECTOR_STALE
        SymbolDescribeRejection.NOT_FOUND -> SymbolDescribeRejectionWireDocument.NOT_FOUND
    }

internal fun SymbolDescribeRejectionWireDocument.toContract(): SymbolDescribeRejection =
    when (this) {
        SymbolDescribeRejectionWireDocument.WORKSPACE_NOT_READY ->
            SymbolDescribeRejection.WORKSPACE_NOT_READY
        SymbolDescribeRejectionWireDocument.SELECTOR_STALE ->
            SymbolDescribeRejection.SELECTOR_STALE
        SymbolDescribeRejectionWireDocument.NOT_FOUND -> SymbolDescribeRejection.NOT_FOUND
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
    RelationReadQualificationWireDocument = RelationReadQualificationWireDocument(
    knownMinimum = knownMinimum.value,
    limitations = limitations.map(RelationLimitationDocument::toWireDocument),
    continuation = continuation.value,
)

internal fun RelationReadQualificationWireDocument.toContract():
    WireDocumentConversion<RelationReadQualification> =
    RelationKnownMinimumDocument.parse(knownMinimum).toWireDocumentConversion()
        .flatMapConverted { admittedMinimum ->
            RelationContinuationDocument.parse(continuation).toWireDocumentConversion()
                .flatMapConverted { admittedContinuation ->
                    RelationReadQualification.create(
                        admittedMinimum,
                        limitations.map(RelationLimitationWireDocument::toContract),
                        admittedContinuation,
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
    }

internal fun RelationReadRejectionWireDocument.toContract(): RelationReadRejection =
    when (this) {
        RelationReadRejectionWireDocument.WORKSPACE_NOT_READY ->
            RelationReadRejection.WORKSPACE_NOT_READY
        RelationReadRejectionWireDocument.SELECTOR_STALE -> RelationReadRejection.SELECTOR_STALE
        RelationReadRejectionWireDocument.RELATION_UNSUPPORTED ->
            RelationReadRejection.RELATION_UNSUPPORTED
    }

internal fun TraversalRunQualification.toWireDocument():
    TraversalRunQualificationWireDocument = TraversalRunQualificationWireDocument(
    limitations = limitations.map(TraversalLimitationDocument::toWireDocument),
    relationLimitations = relationLimitations.map(RelationLimitationDocument::toWireDocument),
    continuation = continuation.value,
)

internal fun TraversalRunQualificationWireDocument.toContract():
    WireDocumentConversion<TraversalRunQualification> =
    TraversalContinuationDocument.parse(continuation).toWireDocumentConversion()
        .flatMapConverted { admittedContinuation ->
            TraversalRunQualification.create(
                limitations.map(TraversalLimitationWireDocument::toContract),
                relationLimitations.map(RelationLimitationWireDocument::toContract),
                admittedContinuation,
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
    }

internal fun TraversalRunRejectionWireDocument.toContract(): TraversalRunRejection =
    when (this) {
        TraversalRunRejectionWireDocument.WORKSPACE_NOT_READY ->
            TraversalRunRejection.WORKSPACE_NOT_READY
        TraversalRunRejectionWireDocument.SELECTOR_STALE -> TraversalRunRejection.SELECTOR_STALE
        TraversalRunRejectionWireDocument.TOPOLOGY_BUILD_REQUIRED ->
            TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED
        TraversalRunRejectionWireDocument.PLAN_REJECTED -> TraversalRunRejection.PLAN_REJECTED
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
