package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints

/** One admitted project and authority; the owning host checks epoch freshness around each call. */
class ProjectBoundIntellijSymbolPorts private constructor(
    val discovery: SymbolCompilerPort,
    val exact: SymbolExactCompilerPort,
) {
    companion object {
        fun create(
            project: Project,
            authority: SemanticReadAuthority,
            model: WorkspaceSearchScopeModel,
            fileAdmission: IntellijSemanticSourceFileAdmission,
        ): ProjectBoundIntellijSymbolPorts {
            val compiledModel = WorkspaceSearchScopeModelCompilation.Compiled(model)
            return ProjectBoundIntellijSymbolPorts(
                SymbolCompilerPort { request ->
                    if (request.scope.scope.unsupportedByBoundHost()) {
                        SymbolCompilation.Rejected(SymbolCompilerRejection.SCOPE_REJECTED)
                    } else if (request.scope.lease != authority || project.isDisposed) {
                        SymbolCompilation.Rejected(SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
                    } else IntellijSymbolCompilerAdapter(IntellijNativeDiscoveryAdapter(
                        IntellijSearchScopeQueryAdapter(IntellijSearchScopeCompiler(request.constraints) { path ->
                            fileAdmission.admits(path, request.constraints.sourceSets)
                        }),
                    )).compile(project, request, compiledModel)
                },
                object : SymbolExactCompilerPort {
                    private fun exact(constraints: SymbolDiscoveryConstraints) = IntellijSymbolExactCompilerAdapter(
                        IntellijSymbolSelectorResolver(IntellijSearchScopeQueryAdapter(
                            IntellijSearchScopeCompiler(constraints) { path -> fileAdmission.admits(path, constraints.sourceSets) },
                        )),
                    )

                    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionCompilation =
                        if (request.selection.scope.unsupportedByBoundHost()) {
                            SymbolResolutionCompilation.Rejected(SymbolExactCompilerRejection.SCOPE_REJECTED)
                        } else exact(request.selection.constraints).resolve(project, authority, request, compiledModel)

                    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionCompilation =
                        if (request.selector.scope.unsupportedByBoundHost()) {
                            SymbolDescriptionCompilation.Rejected(SymbolExactCompilerRejection.SCOPE_REJECTED)
                        } else exact(request.selector.constraints).describe(project, authority, request, compiledModel)
                },
            )
        }
    }
}

private fun SymbolSearchScope.unsupportedByBoundHost(): Boolean =
    generatedSources == SymbolGeneratedSourcePolicy.INCLUDE ||
        this is SymbolSearchScope.Workspace && libraries == SymbolLibraryPolicy.INCLUDE
