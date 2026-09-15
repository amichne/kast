package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.ProtocolText

internal fun DiagnosticCheckQualification.toWireDocument(): DiagnosticCheckQualificationWireDocument =
    DiagnosticCheckQualificationWireDocument(
        knownDiagnosticCount = knownDiagnosticCount.value,
        resultLimitReached = resultLimitReached,
        continuation = continuation,
        analyzedFiles = analyzedFiles.map { it.value },
        limitations =
            limitations.map { limitation ->
                DiagnosticLimitationWireDocument(
                    limitation.file.value,
                    limitation.reason.toWireDocument(),
                )
            },
    )

internal fun DiagnosticCheckQualificationWireDocument.toContract():
    WireDocumentConversion<DiagnosticCheckQualification> =
    DiagnosticKnownCountDocument.parse(knownDiagnosticCount).toWireDocumentConversion().flatMapConverted { admittedCount
        ->
        combineConverted(
                analyzedFiles.convertEach { raw -> raw.protocolQualificationText() },
                limitations.convertEach(DiagnosticLimitationWireDocument::toContract),
            ) { admittedFiles, admittedLimitations ->
                DiagnosticCheckQualification.create(
                        knownDiagnosticCount = admittedCount,
                        resultLimitReached = resultLimitReached,
                        analyzedFiles = admittedFiles,
                        limitations = admittedLimitations,
                        continuation = continuation,
                    )
                    .toWireDocumentConversion()
            }
            .flattenConverted()
    }

private fun DiagnosticLimitationWireDocument.toContract(): WireDocumentConversion<DiagnosticLimitationDocument> =
    file.protocolQualificationText().mapConverted { admittedFile ->
        DiagnosticLimitationDocument(admittedFile, reason.toContract())
    }

private fun String.protocolQualificationText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()

private fun DiagnosticLimitationReasonDocument.toWireDocument(): DiagnosticLimitationReasonWireDocument =
    when (this) {
        DiagnosticLimitationReasonDocument.FILE_UNAVAILABLE -> DiagnosticLimitationReasonWireDocument.FILE_UNAVAILABLE
        DiagnosticLimitationReasonDocument.OUTSIDE_SOURCE_CONTENT ->
            DiagnosticLimitationReasonWireDocument.OUTSIDE_SOURCE_CONTENT
        DiagnosticLimitationReasonDocument.INDEXING -> DiagnosticLimitationReasonWireDocument.INDEXING
        DiagnosticLimitationReasonDocument.PSI_UNAVAILABLE -> DiagnosticLimitationReasonWireDocument.PSI_UNAVAILABLE
        DiagnosticLimitationReasonDocument.UNSUPPORTED_FILE_KIND ->
            DiagnosticLimitationReasonWireDocument.UNSUPPORTED_FILE_KIND
        DiagnosticLimitationReasonDocument.UNSUPPORTED_DIAGNOSTIC ->
            DiagnosticLimitationReasonWireDocument.UNSUPPORTED_DIAGNOSTIC
        DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE ->
            DiagnosticLimitationReasonWireDocument.ANALYSIS_UNAVAILABLE
    }

private fun DiagnosticLimitationReasonWireDocument.toContract(): DiagnosticLimitationReasonDocument =
    when (this) {
        DiagnosticLimitationReasonWireDocument.FILE_UNAVAILABLE -> DiagnosticLimitationReasonDocument.FILE_UNAVAILABLE
        DiagnosticLimitationReasonWireDocument.OUTSIDE_SOURCE_CONTENT ->
            DiagnosticLimitationReasonDocument.OUTSIDE_SOURCE_CONTENT
        DiagnosticLimitationReasonWireDocument.INDEXING -> DiagnosticLimitationReasonDocument.INDEXING
        DiagnosticLimitationReasonWireDocument.PSI_UNAVAILABLE -> DiagnosticLimitationReasonDocument.PSI_UNAVAILABLE
        DiagnosticLimitationReasonWireDocument.UNSUPPORTED_FILE_KIND ->
            DiagnosticLimitationReasonDocument.UNSUPPORTED_FILE_KIND
        DiagnosticLimitationReasonWireDocument.UNSUPPORTED_DIAGNOSTIC ->
            DiagnosticLimitationReasonDocument.UNSUPPORTED_DIAGNOSTIC
        DiagnosticLimitationReasonWireDocument.ANALYSIS_UNAVAILABLE ->
            DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE
    }

internal fun DiagnosticCheckRejection.toWireDocument(): DiagnosticCheckRejectionWireDocument =
    when (this) {
        DiagnosticCheckRejection.ENUMERATION_INDEX_MODE_UNSUPPORTED ->
            DiagnosticCheckRejectionWireDocument.ENUMERATION_INDEX_MODE_UNSUPPORTED
        DiagnosticCheckRejection.EXECUTION_TIME_GRANT_TOO_SMALL ->
            DiagnosticCheckRejectionWireDocument.EXECUTION_TIME_GRANT_TOO_SMALL
        DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE ->
            DiagnosticCheckRejectionWireDocument.CONTINUATION_UNAVAILABLE
        DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH ->
            DiagnosticCheckRejectionWireDocument.CONTINUATION_REQUEST_MISMATCH
        DiagnosticCheckRejection.STALE_CONTINUATION -> DiagnosticCheckRejectionWireDocument.STALE_CONTINUATION
        DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED ->
            DiagnosticCheckRejectionWireDocument.CONTINUATION_CAPACITY_EXCEEDED
        DiagnosticCheckRejection.ENUMERATION_WORK_GRANT_TOO_SMALL ->
            DiagnosticCheckRejectionWireDocument.ENUMERATION_WORK_GRANT_TOO_SMALL
        DiagnosticCheckRejection.ENUMERATION_TIME_GRANT_TOO_SMALL ->
            DiagnosticCheckRejectionWireDocument.ENUMERATION_TIME_GRANT_TOO_SMALL
        DiagnosticCheckRejection.ENUMERATION_RETENTION_EXCEEDED ->
            DiagnosticCheckRejectionWireDocument.ENUMERATION_RETENTION_EXCEEDED
        DiagnosticCheckRejection.COMPILER_UNIT_GRANT_TOO_SMALL ->
            DiagnosticCheckRejectionWireDocument.COMPILER_UNIT_GRANT_TOO_SMALL
        DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION ->
            DiagnosticCheckRejectionWireDocument.COMPILER_CONTRACT_VIOLATION
        DiagnosticCheckRejection.WORKSPACE_INDEX_UNAVAILABLE ->
            DiagnosticCheckRejectionWireDocument.WORKSPACE_INDEX_UNAVAILABLE
        DiagnosticCheckRejection.WORKSPACE_ROOT_MISMATCH -> DiagnosticCheckRejectionWireDocument.WORKSPACE_ROOT_MISMATCH
        DiagnosticCheckRejection.STALE_GENERATION -> DiagnosticCheckRejectionWireDocument.STALE_GENERATION
        DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL -> DiagnosticCheckRejectionWireDocument.OUTPUT_GRANT_TOO_SMALL

        DiagnosticCheckRejection.WORKSPACE_NOT_READY -> DiagnosticCheckRejectionWireDocument.WORKSPACE_NOT_READY
        DiagnosticCheckRejection.SCOPE_REJECTED -> DiagnosticCheckRejectionWireDocument.SCOPE_REJECTED
        DiagnosticCheckRejection.SCOPE_EMPTY -> DiagnosticCheckRejectionWireDocument.SCOPE_EMPTY
        DiagnosticCheckRejection.SCOPE_LIMIT_EXCEEDED -> DiagnosticCheckRejectionWireDocument.SCOPE_LIMIT_EXCEEDED
        DiagnosticCheckRejection.SCOPE_UNAVAILABLE -> DiagnosticCheckRejectionWireDocument.SCOPE_UNAVAILABLE
    }

internal fun DiagnosticCheckRejectionWireDocument.toContract(): DiagnosticCheckRejection =
    when (this) {
        DiagnosticCheckRejectionWireDocument.ENUMERATION_INDEX_MODE_UNSUPPORTED ->
            DiagnosticCheckRejection.ENUMERATION_INDEX_MODE_UNSUPPORTED
        DiagnosticCheckRejectionWireDocument.EXECUTION_TIME_GRANT_TOO_SMALL ->
            DiagnosticCheckRejection.EXECUTION_TIME_GRANT_TOO_SMALL
        DiagnosticCheckRejectionWireDocument.CONTINUATION_UNAVAILABLE ->
            DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE
        DiagnosticCheckRejectionWireDocument.CONTINUATION_REQUEST_MISMATCH ->
            DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH
        DiagnosticCheckRejectionWireDocument.STALE_CONTINUATION -> DiagnosticCheckRejection.STALE_CONTINUATION
        DiagnosticCheckRejectionWireDocument.CONTINUATION_CAPACITY_EXCEEDED ->
            DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED
        DiagnosticCheckRejectionWireDocument.ENUMERATION_WORK_GRANT_TOO_SMALL ->
            DiagnosticCheckRejection.ENUMERATION_WORK_GRANT_TOO_SMALL
        DiagnosticCheckRejectionWireDocument.ENUMERATION_TIME_GRANT_TOO_SMALL ->
            DiagnosticCheckRejection.ENUMERATION_TIME_GRANT_TOO_SMALL
        DiagnosticCheckRejectionWireDocument.ENUMERATION_RETENTION_EXCEEDED ->
            DiagnosticCheckRejection.ENUMERATION_RETENTION_EXCEEDED
        DiagnosticCheckRejectionWireDocument.COMPILER_UNIT_GRANT_TOO_SMALL ->
            DiagnosticCheckRejection.COMPILER_UNIT_GRANT_TOO_SMALL
        DiagnosticCheckRejectionWireDocument.COMPILER_CONTRACT_VIOLATION ->
            DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION
        DiagnosticCheckRejectionWireDocument.WORKSPACE_INDEX_UNAVAILABLE ->
            DiagnosticCheckRejection.WORKSPACE_INDEX_UNAVAILABLE
        DiagnosticCheckRejectionWireDocument.WORKSPACE_ROOT_MISMATCH -> DiagnosticCheckRejection.WORKSPACE_ROOT_MISMATCH
        DiagnosticCheckRejectionWireDocument.STALE_GENERATION -> DiagnosticCheckRejection.STALE_GENERATION
        DiagnosticCheckRejectionWireDocument.OUTPUT_GRANT_TOO_SMALL -> DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL

        DiagnosticCheckRejectionWireDocument.WORKSPACE_NOT_READY -> DiagnosticCheckRejection.WORKSPACE_NOT_READY
        DiagnosticCheckRejectionWireDocument.SCOPE_REJECTED -> DiagnosticCheckRejection.SCOPE_REJECTED
        DiagnosticCheckRejectionWireDocument.SCOPE_EMPTY -> DiagnosticCheckRejection.SCOPE_EMPTY
        DiagnosticCheckRejectionWireDocument.SCOPE_LIMIT_EXCEEDED -> DiagnosticCheckRejection.SCOPE_LIMIT_EXCEEDED
        DiagnosticCheckRejectionWireDocument.SCOPE_UNAVAILABLE -> DiagnosticCheckRejection.SCOPE_UNAVAILABLE
    }
