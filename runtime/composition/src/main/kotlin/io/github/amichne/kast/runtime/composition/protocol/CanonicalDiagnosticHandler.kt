package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticIncompleteCoverage
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.InvalidPathException
import java.nio.file.Path
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest as DomainDiagnosticRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult as DomainDiagnosticResult

internal class CanonicalDiagnosticCheckHandler(
    private val workspace: WorkspaceInspectionOperations,
    private val operations: DiagnosticOperations,
) : OperationHandler<
    DiagnosticCheckRequest,
    DiagnosticCheckResult,
    DiagnosticCheckQualification,
    DiagnosticCheckRejection,
    > {
    override suspend fun execute(request: DiagnosticCheckRequest): OperationOutcome<
        DiagnosticCheckResult,
        DiagnosticCheckQualification,
        DiagnosticCheckRejection,
        > {
        val ready = when (val state = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            else -> return OperationOutcome.Rejected(DiagnosticCheckRejection.WORKSPACE_NOT_READY)
        }
        val scope = when (val admitted = admitDiagnosticScope(ready, request.scope)) {
            is DiagnosticScopeAdmission.Admitted -> admitted.scope
            DiagnosticScopeAdmission.Rejected ->
                return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        }
        return when (val result = operations.check(DomainDiagnosticRequest(scope))) {
            is DomainDiagnosticResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainDiagnosticResult.Complete -> project(
                result.batch.facts,
                scope,
                request.limit.value,
                DiagnosticProjection.Complete(result.coverage.analyzedFiles),
            )
            is DomainDiagnosticResult.Qualified -> project(
                result.batch.facts,
                scope,
                request.limit.value,
                DiagnosticProjection.Incomplete(result.coverage),
            )
        }
    }

    private fun project(
        facts: List<DiagnosticFact>,
        scope: DiagnosticScope,
        limit: Int,
        projection: DiagnosticProjection,
    ): OperationOutcome<
        DiagnosticCheckResult,
        DiagnosticCheckQualification,
        DiagnosticCheckRejection,
        > {
        val documents = mutableListOf<DiagnosticDocument>()
        facts.take(limit).forEach { fact ->
            documents += fact.protocolDocument()
                ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.DIAGNOSTIC_CHECK.id,
            scope.lease.generation,
            DiagnosticCheckResult(bounded),
        )
        val resultLimitReached = facts.size > limit
        if (!resultLimitReached && projection is DiagnosticProjection.Complete) {
            return OperationOutcome.Complete(envelope)
        }
        val knownCount = DiagnosticKnownCountDocument.parse(facts.size).refinedOrNull()
            ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        val analyzedFiles = projection.analyzedFiles.map { file ->
            ProtocolText.parse(file.value).refinedOrNull()
                ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        }
        val limitations = when (projection) {
            is DiagnosticProjection.Complete -> emptyList()
            is DiagnosticProjection.Incomplete -> projection.coverage.limitations
                .sortedWith(compareBy({ it.file.value }, { it.reason.ordinal }))
                .map { limitation ->
                    limitation.protocolDocument()
                        ?: return OperationOutcome.Rejected(
                            DiagnosticCheckRejection.SCOPE_REJECTED,
                        )
                }
        }
        val qualification = DiagnosticCheckQualification.create(
            knownCount,
            resultLimitReached,
            analyzedFiles,
            limitations,
        ).refinedOrNull()
            ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        return OperationOutcome.Qualified(envelope, qualification)
    }
}

private sealed interface DiagnosticProjection {
    val analyzedFiles: List<io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile>

    data class Complete(
        override val analyzedFiles: List<io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile>,
    ) : DiagnosticProjection

    data class Incomplete(
        val coverage: DiagnosticIncompleteCoverage,
    ) : DiagnosticProjection {
        override val analyzedFiles = coverage.analyzedFiles
    }
}

private sealed interface DiagnosticScopeAdmission {
    data class Admitted(
        val scope: DiagnosticScope,
    ) : DiagnosticScopeAdmission

    data object Rejected : DiagnosticScopeAdmission
}

/**
 * Proof transition: `(PublishedWorkspace, ProtocolText) -> DiagnosticScopeAdmission`.
 *
 * Admitted establishes one normalized Kotlin path strictly inside the current exact workspace and
 * binds it to the current semantic lease. [DiagnosticScopeAdmission.Rejected] closes invalid,
 * escaped, unsupported, or otherwise unrepresentable scope text. Raw path extraction stays here.
 */
private fun admitDiagnosticScope(
    workspace: PublishedWorkspace,
    document: ProtocolText,
): DiagnosticScopeAdmission {
    val raw = try {
        Path.of(document.value)
    } catch (_: InvalidPathException) {
        return DiagnosticScopeAdmission.Rejected
    }
    val root = Path.of(workspace.root.value)
    val canonical = if (raw.isAbsolute) raw else root.resolve(raw).normalize()
    return when (val admitted = DiagnosticScope.fromCanonicalPaths(workspace.readLease, listOf(canonical))) {
        is Refinement.Refined -> DiagnosticScopeAdmission.Admitted(admitted.value)
        is Refinement.Rejected -> DiagnosticScopeAdmission.Rejected
    }
}

private fun DiagnosticFact.protocolDocument(): DiagnosticDocument? {
    val start = ProtocolOffset.parse(location.range.start.value).refinedOrNull() ?: return null
    val end = ProtocolOffset.parse(location.range.endExclusive.value).refinedOrNull() ?: return null
    val range = DiagnosticRangeDocument.create(start, end).refinedOrNull() ?: return null
    return DiagnosticDocument(
        severity = when (severity) {
            io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.ERROR ->
                DiagnosticSeverityDocument.ERROR
            io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.WARNING ->
                DiagnosticSeverityDocument.WARNING
            io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.INFO ->
                DiagnosticSeverityDocument.INFO
        },
        code = ProtocolText.parse(code.value).refinedOrNull() ?: return null,
        message = ProtocolText.parse(message.value).refinedOrNull() ?: return null,
        location = DiagnosticLocationDocument(
            ProtocolText.parse(location.file.value).refinedOrNull() ?: return null,
            range,
        ),
    )
}

private fun DiagnosticLimitation.protocolDocument(): DiagnosticLimitationDocument? =
    DiagnosticLimitationDocument(
        file = ProtocolText.parse(file.value).refinedOrNull() ?: return null,
        reason = when (reason) {
            DiagnosticLimitationReason.FILE_UNAVAILABLE ->
                DiagnosticLimitationReasonDocument.FILE_UNAVAILABLE
            DiagnosticLimitationReason.OUTSIDE_SOURCE_CONTENT ->
                DiagnosticLimitationReasonDocument.OUTSIDE_SOURCE_CONTENT
            DiagnosticLimitationReason.INDEXING -> DiagnosticLimitationReasonDocument.INDEXING
            DiagnosticLimitationReason.PSI_UNAVAILABLE ->
                DiagnosticLimitationReasonDocument.PSI_UNAVAILABLE
            DiagnosticLimitationReason.UNSUPPORTED_FILE_KIND ->
                DiagnosticLimitationReasonDocument.UNSUPPORTED_FILE_KIND
            DiagnosticLimitationReason.UNSUPPORTED_DIAGNOSTIC ->
                DiagnosticLimitationReasonDocument.UNSUPPORTED_DIAGNOSTIC
            DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE ->
                DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE
        },
    )

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private fun DiagnosticReadRejection.protocol(): DiagnosticCheckRejection = when (this) {
    DiagnosticReadRejection.WORKSPACE_NOT_READY,
    DiagnosticReadRejection.WORKSPACE_ROOT_MISMATCH,
    DiagnosticReadRejection.STALE_GENERATION,
        -> DiagnosticCheckRejection.WORKSPACE_NOT_READY
    DiagnosticReadRejection.WORKSPACE_INDEX_UNAVAILABLE,
    DiagnosticReadRejection.SCOPE_REJECTED,
    DiagnosticReadRejection.COMPILER_CONTRACT_VIOLATION,
        -> DiagnosticCheckRejection.SCOPE_REJECTED
}
