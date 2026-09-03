package io.github.amichne.kast.runtime.ide.host

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
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.InvalidPathException
import java.nio.file.Path
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest as DomainDiagnosticRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult as DomainDiagnosticResult

/** Generated wire bindings for hosted diagnostic reads admitted independently of mutation state. */
internal object HostedDiagnosticProtocol {
    fun bindings(
        workspace: WorkspaceInspectionOperations,
        operations: DiagnosticOperations,
        selectors: HostedExactSelectorOperations,
    ): List<TypedOperationBinding<*, *, *, *>> = listOf(
        TypedOperationBinding(
            CanonicalOperationWireBindings.diagnosticCheck,
            HostedDiagnosticCheckHandler(workspace, operations, selectors),
        ),
    )
}

private class HostedDiagnosticCheckHandler(
    private val workspace: WorkspaceInspectionOperations,
    private val operations: DiagnosticOperations,
    private val selectors: HostedExactSelectorOperations,
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
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return OperationOutcome.Rejected(DiagnosticCheckRejection.WORKSPACE_NOT_READY)
        }
        val scope = admitDiagnosticScope(ready, request.scope)
            ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        return when (val result = operations.check(DomainDiagnosticRequest(scope))) {
            is DomainDiagnosticResult.Rejected ->
                OperationOutcome.Rejected(result.reason.protocolRejection())
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
            documents += fact.protocolDocument(selectors)
                ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        }
        val bounded = BoundedProtocolList.create(documents).valueOrNull()
            ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        val envelope = EvidenceEnvelope(
            CanonicalOperation.DIAGNOSTIC_CHECK.id,
            scope.lease.generation,
            DiagnosticCheckResult(bounded),
        )
        val resultLimitReached = facts.size > limit
        if (!resultLimitReached && projection is DiagnosticProjection.Complete) {
            return OperationOutcome.Complete(envelope)
        }
        val knownCount = DiagnosticKnownCountDocument.parse(facts.size).valueOrNull()
            ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        val analyzedFiles = projection.analyzedFiles.map { file ->
            ProtocolText.parse(file.value).valueOrNull()
                ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
        }
        val limitations = when (projection) {
            is DiagnosticProjection.Complete -> emptyList()
            is DiagnosticProjection.Incomplete -> projection.coverage.limitations
                .sortedWith(compareBy({ it.file.value }, { it.reason.ordinal }))
                .map { limitation ->
                    limitation.protocolDocument()
                        ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
                }
        }
        val qualification = DiagnosticCheckQualification.create(
            knownCount,
            resultLimitReached,
            analyzedFiles,
            limitations,
        ).valueOrNull()
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

private fun admitDiagnosticScope(
    workspace: PublishedWorkspace,
    document: ProtocolText,
): DiagnosticScope? {
    val raw = try {
        Path.of(document.value)
    } catch (_: InvalidPathException) {
        return null
    }
    val root = Path.of(workspace.root.value)
    val canonical = if (raw.isAbsolute) raw else root.resolve(raw).normalize()
    return DiagnosticScope.fromCanonicalPaths(workspace.readLease, listOf(canonical)).valueOrNull()
}

private fun DiagnosticFact.protocolDocument(
    selectors: HostedExactSelectorOperations,
): DiagnosticDocument? {
    val start = ProtocolOffset.parse(location.range.start.value).valueOrNull() ?: return null
    val end = ProtocolOffset.parse(location.range.endExclusive.value).valueOrNull() ?: return null
    val range = DiagnosticRangeDocument.create(start, end).valueOrNull() ?: return null
    val workspaceFile = when (
        val admitted = CanonicalWorkspaceFilePath.fromCanonicalPath(
            scope.lease.workspaceRoot,
            Path.of(location.file.value),
        )
    ) {
        is Refinement.Refined -> SymbolDiscoveryFileIdentity.Workspace(admitted.value)
        is Refinement.Rejected -> return null
    }
    val candidateSelector = when (
        val issued = selectors.issueRangeCandidate(
            scope.lease,
            workspaceFile,
            location.range.start.value,
            location.range.endExclusive.value,
        )
    ) {
        is HostedCandidateIssuance.Issued -> issued.token
        HostedCandidateIssuance.Rejected -> return null
    }
    return DiagnosticDocument(
        severity = when (severity) {
            io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.ERROR ->
                DiagnosticSeverityDocument.ERROR
            io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.WARNING ->
                DiagnosticSeverityDocument.WARNING
            io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity.INFO ->
                DiagnosticSeverityDocument.INFO
        },
        code = ProtocolText.parse(code.value).valueOrNull() ?: return null,
        message = ProtocolText.parse(message.value).valueOrNull() ?: return null,
        location = DiagnosticLocationDocument(
            candidateSelector,
            ProtocolText.parse(location.file.value).valueOrNull() ?: return null,
            range,
        ),
    )
}

private fun DiagnosticLimitation.protocolDocument(): DiagnosticLimitationDocument? =
    DiagnosticLimitationDocument(
        file = ProtocolText.parse(file.value).valueOrNull() ?: return null,
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

private fun DiagnosticReadRejection.protocolRejection(): DiagnosticCheckRejection = when (this) {
    DiagnosticReadRejection.WORKSPACE_NOT_READY,
    DiagnosticReadRejection.WORKSPACE_ROOT_MISMATCH,
    DiagnosticReadRejection.STALE_GENERATION,
        -> DiagnosticCheckRejection.WORKSPACE_NOT_READY
    DiagnosticReadRejection.WORKSPACE_INDEX_UNAVAILABLE,
    DiagnosticReadRejection.SCOPE_REJECTED,
    DiagnosticReadRejection.COMPILER_CONTRACT_VIOLATION,
        -> DiagnosticCheckRejection.SCOPE_REJECTED
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
