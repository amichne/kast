package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission

/** One admitted project and authority; the owning host checks epoch freshness around each call. */
class ProjectBoundIntellijSymbolPorts
private constructor(
    val discovery: SymbolCompilerPort,
    val exact: SymbolExactCompilerPort,
) {
    companion object {
        fun create(
            project: Project,
            authority: SemanticReadAuthority,
            model: WorkspaceSearchScopeModel,
            fileAdmission: IntellijSemanticSourceFileAdmission,
            observation: IntellijReadObservation = IntellijReadObservation.None,
            limits: ReadLimits = ReadLimits.Default,
            capture: IntellijExactRevalidationCapture? = null,
        ): ProjectBoundIntellijSymbolPorts {
            val compiledModel = WorkspaceSearchScopeModelCompilation.Compiled(model)
            return ProjectBoundIntellijSymbolPorts(
                SymbolCompilerPort { request ->
                    if (request.unsupportedByBoundHost()) {
                        SymbolCompilation.Rejected(SymbolCompilerRejection.SCOPE_REJECTED)
                    } else if (request.scope.lease != authority || project.isDisposed) {
                        SymbolCompilation.Rejected(SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
                    } else
                        IntellijSymbolCompilerAdapter(
                                IntellijNativeDiscoveryAdapter(
                                    IntellijSearchScopeQueryAdapter(
                                        IntellijSearchScopeCompiler(
                                            request.constraints,
                                            observation = observation,
                                            sourceRootAdmission = { path ->
                                                fileAdmission.admits(path, request.constraints.sourceSets)
                                            },
                                        ) { path ->
                                            fileAdmission.admits(path, request.constraints.sourceSets)
                                        }
                                    ),
                                    observation,
                                    limits,
                                )
                            )
                            .compile(project, request, compiledModel)
                },
                object : SymbolExactCompilerPort {
                    private fun exact(constraints: SymbolDiscoveryConstraints) =
                        IntellijSymbolExactCompilerAdapter(
                            IntellijSymbolSelectorResolver(
                                IntellijSearchScopeQueryAdapter(
                                    IntellijSearchScopeCompiler(
                                        constraints,
                                        observation = observation,
                                        sourceRootAdmission = { path ->
                                            fileAdmission.admits(path, constraints.sourceSets)
                                        },
                                    ) { path ->
                                        fileAdmission.admits(path, constraints.sourceSets)
                                    }
                                ),
                                observation,
                                limits,
                                capture,
                            )
                        )

                    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionCompilation =
                        if (request.selection.scope.unsupportedByBoundHost(request.selection.constraints)) {
                            SymbolResolutionCompilation.Rejected(SymbolExactCompilerRejection.SCOPE_REJECTED)
                        } else exact(request.selection.constraints).resolve(project, authority, request, compiledModel)

                    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionCompilation =
                        if (request.selector.scope.unsupportedByBoundHost(request.selector.constraints)) {
                            SymbolDescriptionCompilation.Rejected(SymbolExactCompilerRejection.SCOPE_REJECTED)
                        } else exact(request.selector.constraints).describe(project, authority, request, compiledModel)
                },
            )
        }
    }
}

/** Dependency names are unbounded, so a library search needs one exact name in one package family. */
private fun SymbolDiscoveryRequest.unsupportedByBoundHost(): Boolean {
    if (scope.scope.unsupportedByBoundHost(constraints)) return true
    if (!scope.scope.includesLibraries()) return false
    val name = target as? SymbolDiscoveryTarget.Name ?: return true
    return name.match != SymbolDiscoveryMatch.EXACT_NAME || name.kind == SymbolNameDiscoveryKind.FILE
}

private fun SymbolSearchScope.unsupportedByBoundHost(constraints: SymbolDiscoveryConstraints): Boolean =
    generatedSources == SymbolGeneratedSourcePolicy.INCLUDE || includesLibraries() && constraints.packageName == null

private fun SymbolSearchScope.includesLibraries(): Boolean =
    this is SymbolSearchScope.Workspace && libraries == SymbolLibraryPolicy.INCLUDE
