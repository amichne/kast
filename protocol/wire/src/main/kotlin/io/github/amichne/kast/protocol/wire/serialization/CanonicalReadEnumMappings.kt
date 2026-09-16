package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection as RelationCause
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.wire.RelationReadRejectionWireDocument as RelationCode

internal fun SymbolDiscoverLimitation.toWireDocument(): SymbolDiscoverLimitationWireDocument =
    when (this) {
        SymbolDiscoverLimitation.RESULT_LIMIT -> SymbolDiscoverLimitationWireDocument.RESULT_LIMIT
        SymbolDiscoverLimitation.BYTE_LIMIT -> SymbolDiscoverLimitationWireDocument.BYTE_LIMIT
        SymbolDiscoverLimitation.WORK_LIMIT -> SymbolDiscoverLimitationWireDocument.WORK_LIMIT
        SymbolDiscoverLimitation.TIME_LIMIT -> SymbolDiscoverLimitationWireDocument.TIME_LIMIT
        SymbolDiscoverLimitation.DUMB_MODE_TRANSITION -> SymbolDiscoverLimitationWireDocument.DUMB_MODE_TRANSITION
        SymbolDiscoverLimitation.PROVIDER_FAILURE -> SymbolDiscoverLimitationWireDocument.PROVIDER_FAILURE
        SymbolDiscoverLimitation.UNSCOPED_PROVIDER -> SymbolDiscoverLimitationWireDocument.UNSCOPED_PROVIDER
        SymbolDiscoverLimitation.UNSUPPORTED_ITEM -> SymbolDiscoverLimitationWireDocument.UNSUPPORTED_ITEM
        SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE ->
            SymbolDiscoverLimitationWireDocument.EXACT_DEFINITION_UNAVAILABLE
    }

internal fun SymbolDiscoverLimitationWireDocument.toContract(): SymbolDiscoverLimitation =
    when (this) {
        SymbolDiscoverLimitationWireDocument.RESULT_LIMIT -> SymbolDiscoverLimitation.RESULT_LIMIT
        SymbolDiscoverLimitationWireDocument.BYTE_LIMIT -> SymbolDiscoverLimitation.BYTE_LIMIT
        SymbolDiscoverLimitationWireDocument.WORK_LIMIT -> SymbolDiscoverLimitation.WORK_LIMIT
        SymbolDiscoverLimitationWireDocument.TIME_LIMIT -> SymbolDiscoverLimitation.TIME_LIMIT
        SymbolDiscoverLimitationWireDocument.DUMB_MODE_TRANSITION -> SymbolDiscoverLimitation.DUMB_MODE_TRANSITION
        SymbolDiscoverLimitationWireDocument.PROVIDER_FAILURE -> SymbolDiscoverLimitation.PROVIDER_FAILURE
        SymbolDiscoverLimitationWireDocument.UNSCOPED_PROVIDER -> SymbolDiscoverLimitation.UNSCOPED_PROVIDER
        SymbolDiscoverLimitationWireDocument.UNSUPPORTED_ITEM -> SymbolDiscoverLimitation.UNSUPPORTED_ITEM
        SymbolDiscoverLimitationWireDocument.EXACT_DEFINITION_UNAVAILABLE ->
            SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE
    }

internal fun SymbolDiscoverRejection.toWireDocument(): SymbolDiscoverRejectionWireDocument =
    when (this) {
        SymbolDiscoverRejection.WORKSPACE_NOT_READY -> SymbolDiscoverRejectionWireDocument.WORKSPACE_NOT_READY
        SymbolDiscoverRejection.QUERY_REJECTED -> SymbolDiscoverRejectionWireDocument.QUERY_REJECTED
    }

internal fun SymbolDiscoverRejectionWireDocument.toContract(): SymbolDiscoverRejection =
    when (this) {
        SymbolDiscoverRejectionWireDocument.WORKSPACE_NOT_READY -> SymbolDiscoverRejection.WORKSPACE_NOT_READY
        SymbolDiscoverRejectionWireDocument.QUERY_REJECTED -> SymbolDiscoverRejection.QUERY_REJECTED
    }

internal fun SymbolInspectQualification.toWireDocument(): SymbolInspectQualificationWireDocument =
    when (this) {
        SymbolInspectQualification.EVIDENCE_INCOMPLETE -> SymbolInspectQualificationWireDocument.EVIDENCE_INCOMPLETE
    }

internal fun SymbolInspectQualificationWireDocument.toContract(): SymbolInspectQualification =
    when (this) {
        SymbolInspectQualificationWireDocument.EVIDENCE_INCOMPLETE -> SymbolInspectQualification.EVIDENCE_INCOMPLETE
    }

internal fun SymbolInspectRejection.toWireDocument(): SymbolInspectRejectionWireDocument =
    when (this) {
        SymbolInspectRejection.WORKSPACE_NOT_READY -> SymbolInspectRejectionWireDocument.WORKSPACE_NOT_READY
        SymbolInspectRejection.SELECTOR_WRONG_KIND -> SymbolInspectRejectionWireDocument.SELECTOR_WRONG_KIND
        SymbolInspectRejection.SELECTOR_MALFORMED -> SymbolInspectRejectionWireDocument.SELECTOR_MALFORMED
        SymbolInspectRejection.SELECTOR_WORKSPACE_MISMATCH ->
            SymbolInspectRejectionWireDocument.SELECTOR_WORKSPACE_MISMATCH
        SymbolInspectRejection.CANDIDATE_STALE -> SymbolInspectRejectionWireDocument.CANDIDATE_STALE
        SymbolInspectRejection.CANDIDATE_NOT_DECLARATION -> SymbolInspectRejectionWireDocument.CANDIDATE_NOT_DECLARATION
        SymbolInspectRejection.EXACT_SELECTOR_STALE -> SymbolInspectRejectionWireDocument.EXACT_SELECTOR_STALE
        SymbolInspectRejection.AMBIGUOUS -> SymbolInspectRejectionWireDocument.AMBIGUOUS
        SymbolInspectRejection.NOT_FOUND -> SymbolInspectRejectionWireDocument.NOT_FOUND
        SymbolInspectRejection.UNSUPPORTED_DECLARATION -> SymbolInspectRejectionWireDocument.UNSUPPORTED_DECLARATION
        SymbolInspectRejection.WORKSPACE_INDEX_UNAVAILABLE ->
            SymbolInspectRejectionWireDocument.WORKSPACE_INDEX_UNAVAILABLE
        SymbolInspectRejection.NATIVE_FAILURE -> SymbolInspectRejectionWireDocument.NATIVE_FAILURE
        SymbolInspectRejection.REVALIDATION_UNRETAINED -> SymbolInspectRejectionWireDocument.REVALIDATION_UNRETAINED
        SymbolInspectRejection.REVALIDATION_EXPIRED -> SymbolInspectRejectionWireDocument.REVALIDATION_EXPIRED
        SymbolInspectRejection.REVALIDATION_CAPACITY -> SymbolInspectRejectionWireDocument.REVALIDATION_CAPACITY
        SymbolInspectRejection.REVALIDATION_WORK_LIMIT_REACHED ->
            SymbolInspectRejectionWireDocument.REVALIDATION_WORK_LIMIT_REACHED
        SymbolInspectRejection.REVALIDATION_TIME_LIMIT_REACHED ->
            SymbolInspectRejectionWireDocument.REVALIDATION_TIME_LIMIT_REACHED
        SymbolInspectRejection.REVALIDATION_RETIRED -> SymbolInspectRejectionWireDocument.REVALIDATION_RETIRED
        SymbolInspectRejection.REVALIDATION_CAPTURE_UNAVAILABLE ->
            SymbolInspectRejectionWireDocument.REVALIDATION_CAPTURE_UNAVAILABLE
        SymbolInspectRejection.REVALIDATION_WORKSPACE_MISMATCH ->
            SymbolInspectRejectionWireDocument.REVALIDATION_WORKSPACE_MISMATCH
        SymbolInspectRejection.REVALIDATION_OWNER_MISMATCH ->
            SymbolInspectRejectionWireDocument.REVALIDATION_OWNER_MISMATCH
        SymbolInspectRejection.REVALIDATION_WORKSPACE_NOT_READY ->
            SymbolInspectRejectionWireDocument.REVALIDATION_WORKSPACE_NOT_READY
        SymbolInspectRejection.REVALIDATION_BASIS_MOVED -> SymbolInspectRejectionWireDocument.REVALIDATION_BASIS_MOVED
        SymbolInspectRejection.REVALIDATION_CONTENT_CHANGED ->
            SymbolInspectRejectionWireDocument.REVALIDATION_CONTENT_CHANGED
        SymbolInspectRejection.REVALIDATION_CONTENT_UNCOMMITTED ->
            SymbolInspectRejectionWireDocument.REVALIDATION_CONTENT_UNCOMMITTED
        SymbolInspectRejection.REVALIDATION_SCOPE_REJECTED ->
            SymbolInspectRejectionWireDocument.REVALIDATION_SCOPE_REJECTED
        SymbolInspectRejection.REVALIDATION_DECLARATION_MISSING ->
            SymbolInspectRejectionWireDocument.REVALIDATION_DECLARATION_MISSING
        SymbolInspectRejection.REVALIDATION_UNSUPPORTED_DECLARATION ->
            SymbolInspectRejectionWireDocument.REVALIDATION_UNSUPPORTED_DECLARATION
        SymbolInspectRejection.REVALIDATION_AMBIGUOUS -> SymbolInspectRejectionWireDocument.REVALIDATION_AMBIGUOUS
        SymbolInspectRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            SymbolInspectRejectionWireDocument.REVALIDATION_COMPILER_IDENTITY_CHANGED
        SymbolInspectRejection.REVALIDATION_COMPILER_UNAVAILABLE ->
            SymbolInspectRejectionWireDocument.REVALIDATION_COMPILER_UNAVAILABLE
    }

internal fun SymbolInspectRejectionWireDocument.toContract(): SymbolInspectRejection =
    when (this) {
        SymbolInspectRejectionWireDocument.WORKSPACE_NOT_READY -> SymbolInspectRejection.WORKSPACE_NOT_READY
        SymbolInspectRejectionWireDocument.SELECTOR_WRONG_KIND -> SymbolInspectRejection.SELECTOR_WRONG_KIND
        SymbolInspectRejectionWireDocument.SELECTOR_MALFORMED -> SymbolInspectRejection.SELECTOR_MALFORMED
        SymbolInspectRejectionWireDocument.SELECTOR_WORKSPACE_MISMATCH ->
            SymbolInspectRejection.SELECTOR_WORKSPACE_MISMATCH
        SymbolInspectRejectionWireDocument.CANDIDATE_STALE -> SymbolInspectRejection.CANDIDATE_STALE
        SymbolInspectRejectionWireDocument.CANDIDATE_NOT_DECLARATION -> SymbolInspectRejection.CANDIDATE_NOT_DECLARATION
        SymbolInspectRejectionWireDocument.EXACT_SELECTOR_STALE -> SymbolInspectRejection.EXACT_SELECTOR_STALE
        SymbolInspectRejectionWireDocument.AMBIGUOUS -> SymbolInspectRejection.AMBIGUOUS
        SymbolInspectRejectionWireDocument.NOT_FOUND -> SymbolInspectRejection.NOT_FOUND
        SymbolInspectRejectionWireDocument.UNSUPPORTED_DECLARATION -> SymbolInspectRejection.UNSUPPORTED_DECLARATION
        SymbolInspectRejectionWireDocument.WORKSPACE_INDEX_UNAVAILABLE ->
            SymbolInspectRejection.WORKSPACE_INDEX_UNAVAILABLE
        SymbolInspectRejectionWireDocument.NATIVE_FAILURE -> SymbolInspectRejection.NATIVE_FAILURE
        SymbolInspectRejectionWireDocument.REVALIDATION_UNRETAINED -> SymbolInspectRejection.REVALIDATION_UNRETAINED
        SymbolInspectRejectionWireDocument.REVALIDATION_EXPIRED -> SymbolInspectRejection.REVALIDATION_EXPIRED
        SymbolInspectRejectionWireDocument.REVALIDATION_CAPACITY -> SymbolInspectRejection.REVALIDATION_CAPACITY
        SymbolInspectRejectionWireDocument.REVALIDATION_WORK_LIMIT_REACHED ->
            SymbolInspectRejection.REVALIDATION_WORK_LIMIT_REACHED
        SymbolInspectRejectionWireDocument.REVALIDATION_TIME_LIMIT_REACHED ->
            SymbolInspectRejection.REVALIDATION_TIME_LIMIT_REACHED
        SymbolInspectRejectionWireDocument.REVALIDATION_RETIRED -> SymbolInspectRejection.REVALIDATION_RETIRED
        SymbolInspectRejectionWireDocument.REVALIDATION_CAPTURE_UNAVAILABLE ->
            SymbolInspectRejection.REVALIDATION_CAPTURE_UNAVAILABLE
        SymbolInspectRejectionWireDocument.REVALIDATION_WORKSPACE_MISMATCH ->
            SymbolInspectRejection.REVALIDATION_WORKSPACE_MISMATCH
        SymbolInspectRejectionWireDocument.REVALIDATION_OWNER_MISMATCH ->
            SymbolInspectRejection.REVALIDATION_OWNER_MISMATCH
        SymbolInspectRejectionWireDocument.REVALIDATION_WORKSPACE_NOT_READY ->
            SymbolInspectRejection.REVALIDATION_WORKSPACE_NOT_READY
        SymbolInspectRejectionWireDocument.REVALIDATION_BASIS_MOVED -> SymbolInspectRejection.REVALIDATION_BASIS_MOVED
        SymbolInspectRejectionWireDocument.REVALIDATION_CONTENT_CHANGED ->
            SymbolInspectRejection.REVALIDATION_CONTENT_CHANGED
        SymbolInspectRejectionWireDocument.REVALIDATION_CONTENT_UNCOMMITTED ->
            SymbolInspectRejection.REVALIDATION_CONTENT_UNCOMMITTED
        SymbolInspectRejectionWireDocument.REVALIDATION_SCOPE_REJECTED ->
            SymbolInspectRejection.REVALIDATION_SCOPE_REJECTED
        SymbolInspectRejectionWireDocument.REVALIDATION_DECLARATION_MISSING ->
            SymbolInspectRejection.REVALIDATION_DECLARATION_MISSING
        SymbolInspectRejectionWireDocument.REVALIDATION_UNSUPPORTED_DECLARATION ->
            SymbolInspectRejection.REVALIDATION_UNSUPPORTED_DECLARATION
        SymbolInspectRejectionWireDocument.REVALIDATION_AMBIGUOUS -> SymbolInspectRejection.REVALIDATION_AMBIGUOUS
        SymbolInspectRejectionWireDocument.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            SymbolInspectRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED
        SymbolInspectRejectionWireDocument.REVALIDATION_COMPILER_UNAVAILABLE ->
            SymbolInspectRejection.REVALIDATION_COMPILER_UNAVAILABLE
    }

internal fun RelationKindDocument.toWireDocument(): RelationKindWireDocument =
    when (this) {
        RelationKindDocument.REFERENCES -> RelationKindWireDocument.REFERENCES
        RelationKindDocument.CALLERS -> RelationKindWireDocument.CALLERS
        RelationKindDocument.CALLEES -> RelationKindWireDocument.CALLEES
        RelationKindDocument.IMPLEMENTATIONS -> RelationKindWireDocument.IMPLEMENTATIONS
        RelationKindDocument.INHERITORS -> RelationKindWireDocument.INHERITORS
        RelationKindDocument.OVERRIDES -> RelationKindWireDocument.OVERRIDES
        RelationKindDocument.TYPE_USES -> RelationKindWireDocument.TYPE_USES
    }

internal fun RelationKindWireDocument.toContract(): RelationKindDocument =
    when (this) {
        RelationKindWireDocument.REFERENCES -> RelationKindDocument.REFERENCES
        RelationKindWireDocument.CALLERS -> RelationKindDocument.CALLERS
        RelationKindWireDocument.CALLEES -> RelationKindDocument.CALLEES
        RelationKindWireDocument.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
        RelationKindWireDocument.INHERITORS -> RelationKindDocument.INHERITORS
        RelationKindWireDocument.OVERRIDES -> RelationKindDocument.OVERRIDES
        RelationKindWireDocument.TYPE_USES -> RelationKindDocument.TYPE_USES
    }

internal fun RelationReadQualification.toWireDocument(): RelationReadQualificationWireDocument =
    when (this) {
        is RelationReadQualification.Resumable ->
            RelationReadQualificationWireDocument.Resumable(
                knownMinimum = knownMinimum.value,
                limitations = limitations.map(RelationLimitationDocument::toWireDocument),
                continuation = continuation.value,
                checkpoint = checkpoint,
                nextAction = nextAction,
            )
        is RelationReadQualification.TerminalIncomplete ->
            RelationReadQualificationWireDocument.TerminalIncomplete(
                knownMinimum = knownMinimum.value,
                limitations = limitations.map(RelationLimitationDocument::toWireDocument),
            )
    }

internal fun RelationReadQualificationWireDocument.toContract(): WireDocumentConversion<RelationReadQualification> =
    when (this) {
        is RelationReadQualificationWireDocument.Resumable ->
            RelationKnownMinimumDocument.parse(knownMinimum).toWireDocumentConversion().flatMapConverted {
                admittedMinimum ->
                RelationContinuationDocument.parse(continuation).toWireDocumentConversion().flatMapConverted {
                    admittedContinuation ->
                    if (admittedContinuation != checkpoint.token) WireDocumentConversion.Rejected
                    else
                        RelationReadQualification.admitResumable(
                                knownMinimum = admittedMinimum,
                                limitations = limitations.map(RelationLimitationWireDocument::toContract),
                                checkpoint = checkpoint,
                                nextAction = nextAction,
                            )
                            .toWireDocumentConversion()
                }
            }
        is RelationReadQualificationWireDocument.TerminalIncomplete ->
            RelationKnownMinimumDocument.parse(knownMinimum).toWireDocumentConversion().flatMapConverted {
                admittedMinimum ->
                RelationReadQualification.terminalIncomplete(
                        admittedMinimum,
                        limitations.map(RelationLimitationWireDocument::toContract),
                    )
                    .toWireDocumentConversion()
            }
    }

internal fun RelationLimitationDocument.toWireDocument(): RelationLimitationWireDocument =
    when (this) {
        RelationLimitationDocument.RESULT_LIMIT_REACHED -> RelationLimitationWireDocument.RESULT_LIMIT_REACHED
        RelationLimitationDocument.BYTE_LIMIT_REACHED -> RelationLimitationWireDocument.BYTE_LIMIT_REACHED
        RelationLimitationDocument.WORK_LIMIT_REACHED -> RelationLimitationWireDocument.WORK_LIMIT_REACHED
        RelationLimitationDocument.TIME_LIMIT_REACHED -> RelationLimitationWireDocument.TIME_LIMIT_REACHED
        RelationLimitationDocument.DUMB_MODE_TRANSITION -> RelationLimitationWireDocument.DUMB_MODE_TRANSITION
        RelationLimitationDocument.UNRESOLVED_TARGET -> RelationLimitationWireDocument.UNRESOLVED_TARGET
        RelationLimitationDocument.UNSUPPORTED_ITEM -> RelationLimitationWireDocument.UNSUPPORTED_ITEM
        RelationLimitationDocument.PROVIDER_FAILURE -> RelationLimitationWireDocument.PROVIDER_FAILURE
        RelationLimitationDocument.PROVIDER_INCOMPLETE -> RelationLimitationWireDocument.PROVIDER_INCOMPLETE
        RelationLimitationDocument.PROVIDER_STALLED -> RelationLimitationWireDocument.PROVIDER_STALLED
    }

internal fun RelationLimitationWireDocument.toContract(): RelationLimitationDocument =
    when (this) {
        RelationLimitationWireDocument.RESULT_LIMIT_REACHED -> RelationLimitationDocument.RESULT_LIMIT_REACHED
        RelationLimitationWireDocument.BYTE_LIMIT_REACHED -> RelationLimitationDocument.BYTE_LIMIT_REACHED
        RelationLimitationWireDocument.WORK_LIMIT_REACHED -> RelationLimitationDocument.WORK_LIMIT_REACHED
        RelationLimitationWireDocument.TIME_LIMIT_REACHED -> RelationLimitationDocument.TIME_LIMIT_REACHED
        RelationLimitationWireDocument.DUMB_MODE_TRANSITION -> RelationLimitationDocument.DUMB_MODE_TRANSITION
        RelationLimitationWireDocument.UNRESOLVED_TARGET -> RelationLimitationDocument.UNRESOLVED_TARGET
        RelationLimitationWireDocument.UNSUPPORTED_ITEM -> RelationLimitationDocument.UNSUPPORTED_ITEM
        RelationLimitationWireDocument.PROVIDER_FAILURE -> RelationLimitationDocument.PROVIDER_FAILURE
        RelationLimitationWireDocument.PROVIDER_INCOMPLETE -> RelationLimitationDocument.PROVIDER_INCOMPLETE
        RelationLimitationWireDocument.PROVIDER_STALLED -> RelationLimitationDocument.PROVIDER_STALLED
    }

internal fun RelationCause.toWireDocument(): RelationCode =
    when (this) {
        RelationCause.REVALIDATION_WRONG_KIND -> RelationCode.REVALIDATION_WRONG_KIND
        RelationCause.REVALIDATION_UNRETAINED -> RelationCode.REVALIDATION_UNRETAINED
        RelationCause.REVALIDATION_EXPIRED -> RelationCode.REVALIDATION_EXPIRED
        RelationCause.REVALIDATION_CAPACITY -> RelationCode.REVALIDATION_CAPACITY
        RelationCause.REVALIDATION_WORK_LIMIT_REACHED -> RelationCode.REVALIDATION_WORK_LIMIT_REACHED
        RelationCause.REVALIDATION_TIME_LIMIT_REACHED -> RelationCode.REVALIDATION_TIME_LIMIT_REACHED
        RelationCause.REVALIDATION_RETIRED -> RelationCode.REVALIDATION_RETIRED
        RelationCause.REVALIDATION_CAPTURE_UNAVAILABLE -> RelationCode.REVALIDATION_CAPTURE_UNAVAILABLE
        RelationCause.REVALIDATION_WORKSPACE_MISMATCH -> RelationCode.REVALIDATION_WORKSPACE_MISMATCH
        RelationCause.REVALIDATION_OWNER_MISMATCH -> RelationCode.REVALIDATION_OWNER_MISMATCH
        RelationCause.REVALIDATION_WORKSPACE_NOT_READY -> RelationCode.REVALIDATION_WORKSPACE_NOT_READY
        RelationCause.REVALIDATION_BASIS_MOVED -> RelationCode.REVALIDATION_BASIS_MOVED
        RelationCause.REVALIDATION_CONTENT_CHANGED -> RelationCode.REVALIDATION_CONTENT_CHANGED
        RelationCause.REVALIDATION_CONTENT_UNCOMMITTED -> RelationCode.REVALIDATION_CONTENT_UNCOMMITTED
        RelationCause.REVALIDATION_SCOPE_REJECTED -> RelationCode.REVALIDATION_SCOPE_REJECTED
        RelationCause.REVALIDATION_DECLARATION_MISSING -> RelationCode.REVALIDATION_DECLARATION_MISSING
        RelationCause.REVALIDATION_UNSUPPORTED_DECLARATION -> RelationCode.REVALIDATION_UNSUPPORTED_DECLARATION
        RelationCause.REVALIDATION_AMBIGUOUS -> RelationCode.REVALIDATION_AMBIGUOUS
        RelationCause.REVALIDATION_COMPILER_IDENTITY_CHANGED -> RelationCode.REVALIDATION_COMPILER_IDENTITY_CHANGED
        RelationCause.REVALIDATION_COMPILER_UNAVAILABLE -> RelationCode.REVALIDATION_COMPILER_UNAVAILABLE

        RelationCause.SCOPE_REJECTED -> RelationCode.SCOPE_REJECTED
        RelationCause.WORKSPACE_INDEX_UNAVAILABLE -> RelationCode.WORKSPACE_INDEX_UNAVAILABLE
        RelationCause.OUTSIDE_SCOPE -> RelationCode.OUTSIDE_SCOPE
        RelationCause.AMBIGUOUS_SUBJECT -> RelationCode.AMBIGUOUS_SUBJECT
        RelationCause.COMPILER_IDENTITY_UNAVAILABLE -> RelationCode.COMPILER_IDENTITY_UNAVAILABLE
        RelationCause.COMPILER_CONTRACT_VIOLATION -> RelationCode.COMPILER_CONTRACT_VIOLATION

        RelationCause.WORKSPACE_NOT_READY -> RelationCode.WORKSPACE_NOT_READY
        RelationCause.SELECTOR_WRONG_KIND -> RelationCode.SELECTOR_WRONG_KIND
        RelationCause.SELECTOR_MALFORMED -> RelationCode.SELECTOR_MALFORMED
        RelationCause.SELECTOR_WORKSPACE_MISMATCH -> RelationCode.SELECTOR_WORKSPACE_MISMATCH
        RelationCause.SELECTOR_STALE -> RelationCode.SELECTOR_STALE
        RelationCause.RELATION_UNSUPPORTED -> RelationCode.RELATION_UNSUPPORTED
        RelationCause.CONTINUATION_MALFORMED -> RelationCode.CONTINUATION_MALFORMED
        RelationCause.CONTINUATION_UNAVAILABLE -> RelationCode.CONTINUATION_UNAVAILABLE
        RelationCause.CONTINUATION_REQUEST_MISMATCH -> RelationCode.CONTINUATION_REQUEST_MISMATCH
        RelationCause.CONTINUATION_SUBJECT_MISMATCH -> RelationCode.CONTINUATION_SUBJECT_MISMATCH
        RelationCause.CONTINUATION_RELATION_MISMATCH -> RelationCode.CONTINUATION_RELATION_MISMATCH
        RelationCause.CONTINUATION_SCOPE_MISMATCH -> RelationCode.CONTINUATION_SCOPE_MISMATCH
        RelationCause.CONTINUATION_GENERATION_MISMATCH -> RelationCode.CONTINUATION_GENERATION_MISMATCH
        RelationCause.CONTINUATION_CURSOR_MOVED -> RelationCode.CONTINUATION_CURSOR_MOVED
    }

internal fun RelationCode.toContract(): RelationCause =
    when (this) {
        RelationCode.REVALIDATION_WRONG_KIND -> RelationCause.REVALIDATION_WRONG_KIND
        RelationCode.REVALIDATION_UNRETAINED -> RelationCause.REVALIDATION_UNRETAINED
        RelationCode.REVALIDATION_EXPIRED -> RelationCause.REVALIDATION_EXPIRED
        RelationCode.REVALIDATION_CAPACITY -> RelationCause.REVALIDATION_CAPACITY
        RelationCode.REVALIDATION_WORK_LIMIT_REACHED -> RelationCause.REVALIDATION_WORK_LIMIT_REACHED
        RelationCode.REVALIDATION_TIME_LIMIT_REACHED -> RelationCause.REVALIDATION_TIME_LIMIT_REACHED
        RelationCode.REVALIDATION_RETIRED -> RelationCause.REVALIDATION_RETIRED
        RelationCode.REVALIDATION_CAPTURE_UNAVAILABLE -> RelationCause.REVALIDATION_CAPTURE_UNAVAILABLE
        RelationCode.REVALIDATION_WORKSPACE_MISMATCH -> RelationCause.REVALIDATION_WORKSPACE_MISMATCH
        RelationCode.REVALIDATION_OWNER_MISMATCH -> RelationCause.REVALIDATION_OWNER_MISMATCH
        RelationCode.REVALIDATION_WORKSPACE_NOT_READY -> RelationCause.REVALIDATION_WORKSPACE_NOT_READY
        RelationCode.REVALIDATION_BASIS_MOVED -> RelationCause.REVALIDATION_BASIS_MOVED
        RelationCode.REVALIDATION_CONTENT_CHANGED -> RelationCause.REVALIDATION_CONTENT_CHANGED
        RelationCode.REVALIDATION_CONTENT_UNCOMMITTED -> RelationCause.REVALIDATION_CONTENT_UNCOMMITTED
        RelationCode.REVALIDATION_SCOPE_REJECTED -> RelationCause.REVALIDATION_SCOPE_REJECTED
        RelationCode.REVALIDATION_DECLARATION_MISSING -> RelationCause.REVALIDATION_DECLARATION_MISSING
        RelationCode.REVALIDATION_UNSUPPORTED_DECLARATION -> RelationCause.REVALIDATION_UNSUPPORTED_DECLARATION
        RelationCode.REVALIDATION_AMBIGUOUS -> RelationCause.REVALIDATION_AMBIGUOUS
        RelationCode.REVALIDATION_COMPILER_IDENTITY_CHANGED -> RelationCause.REVALIDATION_COMPILER_IDENTITY_CHANGED
        RelationCode.REVALIDATION_COMPILER_UNAVAILABLE -> RelationCause.REVALIDATION_COMPILER_UNAVAILABLE

        RelationCode.SCOPE_REJECTED -> RelationCause.SCOPE_REJECTED
        RelationCode.WORKSPACE_INDEX_UNAVAILABLE -> RelationCause.WORKSPACE_INDEX_UNAVAILABLE
        RelationCode.OUTSIDE_SCOPE -> RelationCause.OUTSIDE_SCOPE
        RelationCode.AMBIGUOUS_SUBJECT -> RelationCause.AMBIGUOUS_SUBJECT
        RelationCode.COMPILER_IDENTITY_UNAVAILABLE -> RelationCause.COMPILER_IDENTITY_UNAVAILABLE
        RelationCode.COMPILER_CONTRACT_VIOLATION -> RelationCause.COMPILER_CONTRACT_VIOLATION

        RelationCode.WORKSPACE_NOT_READY -> RelationCause.WORKSPACE_NOT_READY
        RelationCode.SELECTOR_WRONG_KIND -> RelationCause.SELECTOR_WRONG_KIND
        RelationCode.SELECTOR_MALFORMED -> RelationCause.SELECTOR_MALFORMED
        RelationCode.SELECTOR_WORKSPACE_MISMATCH -> RelationCause.SELECTOR_WORKSPACE_MISMATCH
        RelationCode.SELECTOR_STALE -> RelationCause.SELECTOR_STALE
        RelationCode.RELATION_UNSUPPORTED -> RelationCause.RELATION_UNSUPPORTED
        RelationCode.CONTINUATION_MALFORMED -> RelationCause.CONTINUATION_MALFORMED
        RelationCode.CONTINUATION_UNAVAILABLE -> RelationCause.CONTINUATION_UNAVAILABLE
        RelationCode.CONTINUATION_REQUEST_MISMATCH -> RelationCause.CONTINUATION_REQUEST_MISMATCH
        RelationCode.CONTINUATION_SUBJECT_MISMATCH -> RelationCause.CONTINUATION_SUBJECT_MISMATCH
        RelationCode.CONTINUATION_RELATION_MISMATCH -> RelationCause.CONTINUATION_RELATION_MISMATCH
        RelationCode.CONTINUATION_SCOPE_MISMATCH -> RelationCause.CONTINUATION_SCOPE_MISMATCH
        RelationCode.CONTINUATION_GENERATION_MISMATCH -> RelationCause.CONTINUATION_GENERATION_MISMATCH
        RelationCode.CONTINUATION_CURSOR_MOVED -> RelationCause.CONTINUATION_CURSOR_MOVED
    }
