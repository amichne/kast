package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanInventory
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanPage
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.*
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.nio.file.Path

class CanonicalDiagnosticCheckProtocol(
    private val operations: DiagnosticScanOperations,
    private val authority: QueryReferenceAuthority,
    private val checkpoints: DiagnosticCheckpointStore,
) {
    suspend fun execute(
        request: DiagnosticCheckRequest,
        current: SemanticReadAuthority,
        budget: ResourceBudget,
        report: ExecutionBudgetReport? = null,
    ): OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckRejection> {
        val query =
            when (val parsed = DiagnosticScopeQuery.parse(current, request.path.value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(parsed.failure.protocol())
            }
        val effective =
            budget.copy(
                resultLimit =
                    when (val count = ResultLimit.parse(minOf(request.limit.value, budget.resultLimit.value))) {
                        is Refinement.Refined -> count.value
                        is Refinement.Rejected ->
                            return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
                    }
            )
        val stored =
            when (val admitted = checkpoints.admit(query, request.continuation, effective)) {
                is DiagnosticCheckpointAdmission.Rejected -> return OperationOutcome.Rejected(admitted.reason)
                is DiagnosticCheckpointAdmission.Replay -> admitted.page
                is DiagnosticCheckpointAdmission.Execute -> {
                    val result = operations.scan(admitted.request, effective)
                    when (val published = checkpoints.publish(admitted, result)) {
                        is Refinement.Refined -> published.value
                        is Refinement.Rejected -> return OperationOutcome.Rejected(published.failure)
                    }
                }
            }
        return project(stored, current, report)
    }

    private fun project(
        stored: DiagnosticStoredPage,
        current: SemanticReadAuthority,
        report: ExecutionBudgetReport?,
    ): OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckRejection> {
        val page =
            when (val result = stored.result) {
                is DiagnosticScanResult.Advancing -> result.page
                is DiagnosticScanResult.Complete -> result.page
                is DiagnosticScanResult.Qualified -> result.page
                is DiagnosticScanResult.Rejected -> return OperationOutcome.Rejected(result.reason.protocol())
            }
        val documents =
            page.facts.map { fact ->
                fact.protocolDocument(authority)
                    ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
            }
        val bounded =
            BoundedProtocolList.create(documents).refinedOrNull()
                ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL)
        val progress =
            when (val projected = page.progress(stored.result, report)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(projected.failure)
            }
        val envelope =
            EvidenceEnvelope(
                CanonicalOperation.DIAGNOSTIC_CHECK.id,
                current.evidenceBasis(),
                DiagnosticCheckResult(
                    bounded,
                    progress,
                ),
            )
        if (stored.result is DiagnosticScanResult.Complete) return OperationOutcome.Complete(envelope)
        val limitations =
            page.limitations.sortedWith(compareBy({ it.file.value }, { it.reason.ordinal })).map {
                it.protocolDocument() ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
            }
        val qualification =
            DiagnosticCheckQualification.create(
                    DiagnosticKnownCountDocument.parse(page.knownDiagnosticCount.value).refinedOrNull()
                        ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED),
                    progress.stage == DiagnosticProgressStage.OUTPUT,
                    progress.analyzedFiles,
                    limitations,
                    stored.next.token(),
                )
                .refinedOrNull() ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        return OperationOutcome.Qualified(envelope, qualification)
    }
}

private fun DiagnosticScanPage.progress(
    result: DiagnosticScanResult,
    report: ExecutionBudgetReport?,
): Refinement<DiagnosticProgressDocument, DiagnosticCheckRejection> {
    val analyzed = analyzedFiles.map {
        ProtocolText.parse(it.value).refinedOrNull()
            ?: return Refinement.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
    }
    val inventory =
        when (val inventory = inventory) {
            DiagnosticScanInventory.Enumerating -> DiagnosticInventoryDocument.Enumerating
            is DiagnosticScanInventory.Exhausted ->
                DiagnosticInventoryDocument.Exhausted(
                    ProtocolCount.parse(inventory.files.size).refinedOrNull()
                        ?: return Refinement.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
                )
        }
    val stage =
        when (val result = result) {
            is DiagnosticScanResult.Advancing ->
                when (result.stop) {
                    is DiagnosticScanStop.Enumeration -> DiagnosticProgressStage.ENUMERATION
                    DiagnosticScanStop.AnalysisPending -> DiagnosticProgressStage.ANALYSIS
                    DiagnosticScanStop.OutputPending -> DiagnosticProgressStage.OUTPUT
                }
            else -> DiagnosticProgressStage.FINISHED
        }
    val known =
        when (val parsed = DiagnosticKnownCountDocument.parse(knownDiagnosticCount.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
        }
    return Refinement.Refined(
        DiagnosticProgressDocument(stage, inventory, analyzed, report, result.progressStop(), known)
    )
}

private fun DiagnosticScanRejection.protocol(): DiagnosticCheckRejection =
    when (this) {
        DiagnosticScanRejection.ExecutionTimeGrantTooSmall -> DiagnosticCheckRejection.EXECUTION_TIME_GRANT_TOO_SMALL
        DiagnosticScanRejection.StaleBasis -> DiagnosticCheckRejection.STALE_CONTINUATION
        is DiagnosticScanRejection.Scope -> reason.protocol()
        is DiagnosticScanRejection.Compiler -> reason.protocol()
        is DiagnosticScanRejection.Enumeration ->
            when (val cause = failure) {
                DiagnosticEnumerationFailure.IndexModeUnsupported ->
                    DiagnosticCheckRejection.ENUMERATION_INDEX_MODE_UNSUPPORTED
                is DiagnosticEnumerationFailure.Scope -> cause.reason.protocol()
                DiagnosticEnumerationFailure.RetentionCapacity ->
                    DiagnosticCheckRejection.ENUMERATION_RETENTION_EXCEEDED
                is DiagnosticEnumerationFailure.IncreaseGrant ->
                    when (cause.reason) {
                        DiagnosticEnumerationStop.WORK_LIMIT ->
                            DiagnosticCheckRejection.ENUMERATION_WORK_GRANT_TOO_SMALL
                        DiagnosticEnumerationStop.TIME_LIMIT ->
                            DiagnosticCheckRejection.ENUMERATION_TIME_GRANT_TOO_SMALL
                        DiagnosticEnumerationStop.FILE_LIMIT -> DiagnosticCheckRejection.SCOPE_LIMIT_EXCEEDED
                    }
            }
        DiagnosticScanRejection.IndivisibleUnitExceedsBudget -> DiagnosticCheckRejection.COMPILER_UNIT_GRANT_TOO_SMALL
    }

private fun DiagnosticScopeResolutionFailure.protocol(): DiagnosticCheckRejection =
    when (this) {
        DiagnosticScopeResolutionFailure.INVALID_SCOPE -> DiagnosticCheckRejection.SCOPE_REJECTED
        DiagnosticScopeResolutionFailure.EMPTY -> DiagnosticCheckRejection.SCOPE_EMPTY
        DiagnosticScopeResolutionFailure.LIMIT_EXCEEDED -> DiagnosticCheckRejection.SCOPE_LIMIT_EXCEEDED
        DiagnosticScopeResolutionFailure.UNAVAILABLE -> DiagnosticCheckRejection.SCOPE_UNAVAILABLE
        DiagnosticScopeResolutionFailure.WORKSPACE_NOT_READY -> DiagnosticCheckRejection.WORKSPACE_NOT_READY
    }

private fun DiagnosticFact.protocolDocument(authority: QueryReferenceAuthority): DiagnosticDocument? {
    val start = ProtocolOffset.parse(location.range.start.value).refinedOrNull() ?: return null
    val end = ProtocolOffset.parse(location.range.endExclusive.value).refinedOrNull() ?: return null
    val range = DiagnosticRangeDocument.create(start, end).refinedOrNull() ?: return null
    val workspaceFile =
        when (
            val admitted =
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                    scope.lease.workspaceRoot,
                    Path.of(location.file.value),
                )
        ) {
            is Refinement.Refined -> SymbolDiscoveryFileIdentity.Workspace(admitted.value)
            is Refinement.Rejected -> return null
        }
    val candidateSelector =
        when (
            val issued =
                authority.issueRangeCandidate(
                    scope.lease,
                    workspaceFile,
                    location.range.start.value,
                    location.range.endExclusive.value,
                )
        ) {
            is CandidateSelectorTokenIssuance.Issued -> issued.selector
            is CandidateSelectorTokenIssuance.Rejected -> return null
        }
    return DiagnosticDocument(
        severity =
            when (severity) {
                io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.ERROR -> DiagnosticSeverityDocument.ERROR
                io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.WARNING ->
                    DiagnosticSeverityDocument.WARNING
                io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.INFO -> DiagnosticSeverityDocument.INFO
            },
        code = ProtocolText.parse(code.value).refinedOrNull() ?: return null,
        message = ProtocolText.parse(message.value).refinedOrNull() ?: return null,
        location =
            DiagnosticLocationDocument(
                candidateSelector,
                ProtocolText.parse(location.file.value).refinedOrNull() ?: return null,
                range,
            ),
    )
}

private fun DiagnosticLimitation.protocolDocument(): DiagnosticLimitationDocument? =
    DiagnosticLimitationDocument(
        file = ProtocolText.parse(file.value).refinedOrNull() ?: return null,
        reason =
            when (reason) {
                DiagnosticLimitationReason.FILE_UNAVAILABLE -> DiagnosticLimitationReasonDocument.FILE_UNAVAILABLE
                DiagnosticLimitationReason.OUTSIDE_SOURCE_CONTENT ->
                    DiagnosticLimitationReasonDocument.OUTSIDE_SOURCE_CONTENT
                DiagnosticLimitationReason.INDEXING -> DiagnosticLimitationReasonDocument.INDEXING
                DiagnosticLimitationReason.PSI_UNAVAILABLE -> DiagnosticLimitationReasonDocument.PSI_UNAVAILABLE
                DiagnosticLimitationReason.UNSUPPORTED_FILE_KIND ->
                    DiagnosticLimitationReasonDocument.UNSUPPORTED_FILE_KIND
                DiagnosticLimitationReason.UNSUPPORTED_DIAGNOSTIC ->
                    DiagnosticLimitationReasonDocument.UNSUPPORTED_DIAGNOSTIC
                DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE ->
                    DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE
            },
    )

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun DiagnosticReadRejection.protocol(): DiagnosticCheckRejection =
    when (this) {
        DiagnosticReadRejection.WORKSPACE_NOT_READY -> DiagnosticCheckRejection.WORKSPACE_NOT_READY
        DiagnosticReadRejection.WORKSPACE_ROOT_MISMATCH -> DiagnosticCheckRejection.WORKSPACE_ROOT_MISMATCH
        DiagnosticReadRejection.STALE_GENERATION -> DiagnosticCheckRejection.STALE_GENERATION
        DiagnosticReadRejection.WORKSPACE_INDEX_UNAVAILABLE -> DiagnosticCheckRejection.WORKSPACE_INDEX_UNAVAILABLE
        DiagnosticReadRejection.SCOPE_REJECTED -> DiagnosticCheckRejection.SCOPE_REJECTED
        DiagnosticReadRejection.COMPILER_CONTRACT_VIOLATION -> DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION
    }

private fun DiagnosticScanResult.progressStop(): io.github.amichne.kast.protocol.contract.DiagnosticProgressStop {
    val cause =
        if (this is DiagnosticScanResult.Advancing) stop
        else return io.github.amichne.kast.protocol.contract.DiagnosticProgressStop.FINISHED
    return when (cause) {
        DiagnosticScanStop.AnalysisPending ->
            io.github.amichne.kast.protocol.contract.DiagnosticProgressStop.ANALYSIS_PENDING
        DiagnosticScanStop.OutputPending ->
            io.github.amichne.kast.protocol.contract.DiagnosticProgressStop.OUTPUT_PENDING
        is DiagnosticScanStop.Enumeration ->
            when (cause.reason) {
                DiagnosticEnumerationStop.WORK_LIMIT ->
                    io.github.amichne.kast.protocol.contract.DiagnosticProgressStop.ENUMERATION_WORK_LIMIT
                DiagnosticEnumerationStop.TIME_LIMIT ->
                    io.github.amichne.kast.protocol.contract.DiagnosticProgressStop.ENUMERATION_TIME_LIMIT
                DiagnosticEnumerationStop.FILE_LIMIT ->
                    io.github.amichne.kast.protocol.contract.DiagnosticProgressStop.ENUMERATION_FILE_LIMIT
            }
    }
}

private fun DiagnosticNextPage.token(): ProtocolText? =
    when (this) {
        DiagnosticNextPage.Terminal -> null
        is DiagnosticNextPage.Continue -> token
    }
