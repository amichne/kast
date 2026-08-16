package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import java.nio.file.Path

fun interface InstalledSymbolScopeOperations {
    /**
     * Proof transition: `SemanticReadLease -> WorkspaceSearchScopeModelCompilation`.
     *
     * A compiled result preserves the exact imported Gradle model for the lease; the closed
     * rejected variant remains the only unavailable state. Raw model extraction is prohibited.
     */
    fun compile(lease: SemanticReadLease): WorkspaceSearchScopeModelCompilation
}

/** Request-local native symbol ports bound to one exact installed workspace. */
class InstalledIntellijSymbolPorts private constructor(
    val discovery: SymbolCompilerPort,
    val exact: SymbolExactCompilerPort,
) {
    companion object {
        /**
         * Proof transition: `(CanonicalWorkspaceRoot, WorkspaceInspectionOperations,
         * InstalledSymbolScopeOperations) -> InstalledIntellijSymbolPorts`.
         *
         * Establishes ports that admit only the current exact-root publication before locating a
         * live project. Missing projects and moved generations remain closed compiler rejections;
         * every live IntelliJ value is obtained and consumed within one request call.
         */
        fun create(
            root: CanonicalWorkspaceRoot,
            workspaces: WorkspaceInspectionOperations,
            scopes: InstalledSymbolScopeOperations,
        ): InstalledIntellijSymbolPorts {
            val discoveryAdapter = IntellijSymbolCompilerAdapter()
            val exactAdapter = IntellijSymbolExactCompilerAdapter()
            return InstalledIntellijSymbolPorts(
                discovery = SymbolCompilerPort { request ->
                    val project = exactProject(root) ?: return@SymbolCompilerPort SymbolCompilation.Rejected(
                        SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
                    )
                    val current = workspaces.currentLease()
                        ?: return@SymbolCompilerPort SymbolCompilation.Rejected(
                            SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
                        )
                    if (current != request.scope.lease) {
                        return@SymbolCompilerPort SymbolCompilation.Rejected(
                            SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
                        )
                    }
                    discoveryAdapter.compile(project, request, scopes.compile(current))
                },
                exact = object : SymbolExactCompilerPort {
                    override suspend fun resolve(
                        request: SymbolResolutionRequest,
                    ): SymbolResolutionCompilation {
                        val project = exactProject(root) ?: return unavailableResolution()
                        val current = workspaces.currentLease() ?: return unavailableResolution()
                        return exactAdapter.resolve(project, current, request, scopes.compile(current))
                    }

                    override suspend fun describe(
                        request: ExactSymbolRequest,
                    ): SymbolDescriptionCompilation {
                        val project = exactProject(root) ?: return unavailableDescription()
                        val current = workspaces.currentLease() ?: return unavailableDescription()
                        return exactAdapter.describe(project, current, request, scopes.compile(current))
                    }
                },
            )
        }
    }
}

private fun WorkspaceInspectionOperations.currentLease(): SemanticReadLease? =
    (inspect() as? WorkspaceRuntimeState.Ready)?.workspace?.readLease

private fun exactProject(root: CanonicalWorkspaceRoot): Project? =
    ProjectManager.getInstance().openProjects.singleOrNull { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?.toString() == root.value
    }

private fun unavailableResolution(): SymbolResolutionCompilation = SymbolResolutionCompilation.Rejected(
    SymbolExactCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
)

private fun unavailableDescription(): SymbolDescriptionCompilation = SymbolDescriptionCompilation.Rejected(
    SymbolExactCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
)
