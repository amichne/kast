package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest as DomainDiagnosticRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult as DomainDiagnosticResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
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
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.InvalidPathException
import java.nio.file.Path

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
                DiagnosticProjection.Complete,
            )
            is DomainDiagnosticResult.Qualified -> project(
                result.batch.facts,
                scope,
                request.limit.value,
                DiagnosticProjection.CoverageIncomplete,
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
        val documents = mutableListOf<ProtocolText>()
        facts.take(limit).forEach { fact ->
            when (val document = ProtocolText.parse(fact.protocolProjection())) {
                is Refinement.Refined -> documents += document.value
                is Refinement.Rejected ->
                    return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
            }
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
        return when {
            facts.size > limit -> OperationOutcome.Qualified(
                envelope,
                DiagnosticCheckQualification.RESULT_LIMIT,
            )
            projection == DiagnosticProjection.CoverageIncomplete -> OperationOutcome.Qualified(
                envelope,
                DiagnosticCheckQualification.COVERAGE_INCOMPLETE,
            )
            else -> OperationOutcome.Complete(envelope)
        }
    }
}

private enum class DiagnosticProjection {
    Complete,
    CoverageIncomplete,
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

private fun DiagnosticFact.protocolProjection(): String = buildString {
    append(severity.name.lowercase())
    append(' ')
    append(code.value)
    append(" @ ")
    append(location.file.value)
    append(':')
    append(location.range.start.value)
    append('-')
    append(location.range.endExclusive.value)
    append(' ')
    append(message.value)
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
