package io.github.amichne.kast.diagnostic.service

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Current-generation admission owner for public `diagnostic.check`. */
class DiagnosticService(
    private val workspaces: WorkspaceInspectionOperations,
    private val compiler: DiagnosticCompilerPort,
) : io.github.amichne.kast.diagnostic.contract.DiagnosticOperations {
    /**
     * Proof transition: `(WorkspaceRuntimeState, DiagnosticCheckRequest,
     * DiagnosticCompilation) -> DiagnosticCheckResult`.
     *
     * A complete or qualified result establishes that the exact scope lease remained current
     * before and after compiler work and that compiler output retained identical scope ownership
     * and coverage. [DiagnosticReadRejection] is the closed expected failure. Workspace
     * observation and compiler execution are the only effects.
     */
    override suspend fun check(request: DiagnosticCheckRequest): DiagnosticCheckResult {
        when (val admission = admitCurrentLease(request.scope.lease, DiagnosticAdmissionPhase.INITIAL)) {
            DiagnosticLeaseAdmission.Admitted -> Unit
            is DiagnosticLeaseAdmission.Rejected ->
                return DiagnosticCheckResult.Rejected(admission.reason)
        }
        val compilation = compiler.check(request.scope)
        when (
            val admission = admitCurrentLease(
                request.scope.lease,
                DiagnosticAdmissionPhase.REVALIDATION,
            )
        ) {
            DiagnosticLeaseAdmission.Admitted -> Unit
            is DiagnosticLeaseAdmission.Rejected ->
                return DiagnosticCheckResult.Rejected(admission.reason)
        }
        return when (compilation) {
            is DiagnosticCompilation.Complete -> when (compilation.admitFor(request.scope)) {
                DiagnosticCompilerOutputAdmission.Admitted ->
                    DiagnosticCheckResult.Complete(compilation.batch, compilation.coverage)
                DiagnosticCompilerOutputAdmission.Rejected -> contractRejected()
            }
            is DiagnosticCompilation.Qualified -> when (compilation.admitFor(request.scope)) {
                DiagnosticCompilerOutputAdmission.Admitted ->
                    DiagnosticCheckResult.Qualified(compilation.batch, compilation.coverage)
                DiagnosticCompilerOutputAdmission.Rejected -> contractRejected()
            }
            is DiagnosticCompilation.Rejected ->
                DiagnosticCheckResult.Rejected(compilation.reason.toPublicRejection())
        }
    }

    /**
     * Proof transition: `(WorkspaceRuntimeState, SemanticReadLease,
     * DiagnosticAdmissionPhase) -> DiagnosticLeaseAdmission`.
     *
     * [DiagnosticLeaseAdmission.Admitted] proves the exact ready root and generation.
     * [DiagnosticLeaseAdmission.Rejected] preserves unavailable, root-mismatch, and stale states
     * as [DiagnosticReadRejection]. Raw runtime state remains at workspace publication.
     */
    private fun admitCurrentLease(
        expected: SemanticReadLease,
        phase: DiagnosticAdmissionPhase,
    ): DiagnosticLeaseAdmission {
        val current = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace.readLease
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Stopping,
                -> return DiagnosticLeaseAdmission.Rejected(
                    when (phase) {
                        DiagnosticAdmissionPhase.INITIAL ->
                            DiagnosticReadRejection.WORKSPACE_NOT_READY
                        DiagnosticAdmissionPhase.REVALIDATION ->
                            DiagnosticReadRejection.STALE_GENERATION
                    },
                )
        }
        return when {
            expected.workspaceRoot != current.workspaceRoot ->
                DiagnosticLeaseAdmission.Rejected(
                    DiagnosticReadRejection.WORKSPACE_ROOT_MISMATCH,
                )
            expected.generation != current.generation ->
                DiagnosticLeaseAdmission.Rejected(DiagnosticReadRejection.STALE_GENERATION)
            else -> DiagnosticLeaseAdmission.Admitted
        }
    }
}

private enum class DiagnosticAdmissionPhase {
    INITIAL,
    REVALIDATION,
}

private sealed interface DiagnosticLeaseAdmission {
    data object Admitted : DiagnosticLeaseAdmission

    data class Rejected(
        val reason: DiagnosticReadRejection,
    ) : DiagnosticLeaseAdmission
}

private enum class DiagnosticCompilerOutputAdmission {
    Admitted,
    Rejected,
}

/**
 * Proof transition: `(DiagnosticCompilation.Complete, DiagnosticScope) ->
 * DiagnosticCompilerOutputAdmission`.
 *
 * Admitted proves identical scope ownership, exact file coverage, and generation-bound facts.
 * Rejected is the closed compiler-contract failure consumed at the public service boundary.
 */
private fun DiagnosticCompilation.Complete.admitFor(
    scope: DiagnosticScope,
): DiagnosticCompilerOutputAdmission = if (
    batch.scope === scope &&
    coverage.analyzedFiles == scope.files &&
    batch.facts.all { fact ->
        fact.scope === scope &&
            fact.generation == scope.lease.generation &&
            fact.location.file in scope.files
    }
) {
    DiagnosticCompilerOutputAdmission.Admitted
} else {
    DiagnosticCompilerOutputAdmission.Rejected
}

/**
 * Proof transition: `(DiagnosticCompilation.Qualified, DiagnosticScope) ->
 * DiagnosticCompilerOutputAdmission`.
 *
 * Admitted proves identical scope ownership, explicit non-empty limitations, total file
 * accounting, and generation-bound facts. Rejected is the closed compiler-contract failure.
 */
private fun DiagnosticCompilation.Qualified.admitFor(
    scope: DiagnosticScope,
): DiagnosticCompilerOutputAdmission {
    val analyzed = coverage.analyzedFiles.toSet()
    val limited = coverage.limitations.map { limitation -> limitation.file }.toSet()
    return if (
        batch.scope === scope &&
        coverage.limitations.isNotEmpty() &&
        analyzed.intersect(limited).isEmpty() &&
        analyzed + limited == scope.files.toSet() &&
        batch.facts.all { fact ->
            fact.scope === scope &&
                fact.generation == scope.lease.generation &&
                fact.location.file in scope.files
        }
    ) {
        DiagnosticCompilerOutputAdmission.Admitted
    } else {
        DiagnosticCompilerOutputAdmission.Rejected
    }
}

private fun contractRejected(): DiagnosticCheckResult.Rejected = DiagnosticCheckResult.Rejected(
    DiagnosticReadRejection.COMPILER_CONTRACT_VIOLATION,
)

private fun DiagnosticCompilerRejection.toPublicRejection(): DiagnosticReadRejection = when (this) {
    DiagnosticCompilerRejection.WORKSPACE_ROOT_MISMATCH ->
        DiagnosticReadRejection.WORKSPACE_ROOT_MISMATCH
    DiagnosticCompilerRejection.GENERATION_MOVED -> DiagnosticReadRejection.STALE_GENERATION
    DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
        DiagnosticReadRejection.WORKSPACE_INDEX_UNAVAILABLE
    DiagnosticCompilerRejection.SCOPE_REJECTED -> DiagnosticReadRejection.SCOPE_REJECTED
    DiagnosticCompilerRejection.COMPILER_CONTRACT_VIOLATION ->
        DiagnosticReadRejection.COMPILER_CONTRACT_VIOLATION
}
