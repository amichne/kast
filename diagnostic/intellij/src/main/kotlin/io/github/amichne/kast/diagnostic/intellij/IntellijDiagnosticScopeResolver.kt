package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectSourceFiles
import io.github.amichne.kast.workspace.intellij.read.ProjectSourceFileFailure

/** Source enumeration is bounded independently of the returned diagnostic count. */
internal suspend fun resolveDiagnosticScope(
    project: Project,
    workspaces: WorkspaceInspectionOperations,
    query: DiagnosticScopeQuery,
): Refinement<DiagnosticScope, DiagnosticScopeResolutionFailure> = readAction {
    val current = (workspaces.inspect() as? WorkspaceRuntimeState.Ready)?.workspace?.readLease
    if (project.isDisposed || current != query.lease) {
        return@readAction Refinement.Rejected(DiagnosticScopeResolutionFailure.WORKSPACE_NOT_READY)
    }
    val paths = when (val result = IntellijProjectSourceFiles.collect(
        project, query.lease.workspaceRoot, query.path, diagnosticScopeBudget,
    )) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return@readAction Refinement.Rejected(when (result.failure) {
            ProjectSourceFileFailure.INVALID_SCOPE -> DiagnosticScopeResolutionFailure.INVALID_SCOPE
            ProjectSourceFileFailure.UNAVAILABLE -> DiagnosticScopeResolutionFailure.UNAVAILABLE
            ProjectSourceFileFailure.LIMIT_EXCEEDED -> DiagnosticScopeResolutionFailure.LIMIT_EXCEEDED
        })
    }
    if (paths.isEmpty()) return@readAction Refinement.Rejected(DiagnosticScopeResolutionFailure.EMPTY)
    when (val admitted = DiagnosticScope.fromCanonicalPaths(query.lease, paths)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
    }
}

internal val diagnosticScopeBudget = ResourceBudget(
    resultLimit = ResultLimit.parse(256).constant(),
    workUnitLimit = WorkUnitLimit.parse(20_000).constant(),
    elapsedTimeLimit = ElapsedTimeLimitMillis.parse(2_000).constant(),
)

private fun <Value, Failure> Refinement<Value, Failure>.constant(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Invalid diagnostic scope budget: $failure")
}
