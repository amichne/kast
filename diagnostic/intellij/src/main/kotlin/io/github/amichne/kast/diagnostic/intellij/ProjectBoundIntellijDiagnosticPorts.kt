package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolver
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectSourceFiles
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import io.github.amichne.kast.workspace.intellij.read.ProjectSourceFileFailure
import java.nio.file.Path

/** Diagnostic enumeration and K2 reads in the admitted project, with no workspace lookup. */
class ProjectBoundIntellijDiagnosticPorts private constructor(
    val compiler: DiagnosticCompilerPort,
    val scopes: DiagnosticScopeResolver,
) {
    companion object {
        fun create(
            project: Project,
            authority: SemanticReadAuthority,
            model: WorkspaceSearchScopeModel,
            fileAdmission: IntellijSemanticSourceFileAdmission,
            limits: ReadLimits = ReadLimits.Default,
        ): ProjectBoundIntellijDiagnosticPorts {
            fun admits(path: Path): Boolean = model.workspaceRoot == authority.workspaceRoot &&
                model.sourceRoots.any { path.startsWith(Path.of(it.sourceRoot.value)) } &&
                fileAdmission.admits(path, SymbolDiscoverySourceSets.All)
            val adapter = IntellijDiagnosticCompilerAdapter(
                IntellijDiagnosticCompilerQuery(::admits),
                LoggingIntellijDiagnosticCompilationObserver,
            )
            return ProjectBoundIntellijDiagnosticPorts(
                compiler = DiagnosticCompilerPort { scope ->
                    if (model.workspaceRoot != authority.workspaceRoot) {
                        DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_ROOT_MISMATCH)
                    } else adapter.read(project, authority, scope)
                },
                scopes = DiagnosticScopeResolver { query -> readAction {
                    if (project.isDisposed || query.lease != authority || model.workspaceRoot != authority.workspaceRoot) {
                        return@readAction Refinement.Rejected(DiagnosticScopeResolutionFailure.WORKSPACE_NOT_READY)
                    }
                    val paths = when (val result = IntellijProjectSourceFiles.collect(
                        project, authority.workspaceRoot, query.path, diagnosticScopeBudget(limits), limits,
                    )) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> return@readAction Refinement.Rejected(when (result.failure) {
                            ProjectSourceFileFailure.INVALID_SCOPE -> DiagnosticScopeResolutionFailure.INVALID_SCOPE
                            ProjectSourceFileFailure.UNAVAILABLE -> DiagnosticScopeResolutionFailure.UNAVAILABLE
                            ProjectSourceFileFailure.LIMIT_EXCEEDED -> DiagnosticScopeResolutionFailure.LIMIT_EXCEEDED
                        })
                    }
                    // A diagnostic scope cannot represent partial enumeration; reject unsupported coverage.
                    if (paths.any { !admits(it) }) {
                        return@readAction Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                    }
                    if (paths.isEmpty()) return@readAction Refinement.Rejected(DiagnosticScopeResolutionFailure.EMPTY)
                    when (val scope = DiagnosticScope.fromCanonicalPaths(authority, paths)) {
                        is Refinement.Refined -> scope
                        is Refinement.Rejected -> Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                    }
                } },
            )
        }
    }
}
