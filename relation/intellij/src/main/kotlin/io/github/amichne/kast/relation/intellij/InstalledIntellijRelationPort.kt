package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import java.nio.file.Path

fun interface InstalledRelationScopeOperations {
    /**
     * Proof transition: `SemanticReadLease -> WorkspaceSearchScopeModelCompilation`.
     *
     * A compiled result preserves the exact imported Gradle model for the lease; the closed
     * rejected variant remains the only unavailable state. Raw model extraction is prohibited.
     */
    fun compile(lease: SemanticReadLease): WorkspaceSearchScopeModelCompilation
}

/**
 * Proof transition: `(CanonicalWorkspaceRoot, WorkspaceInspectionOperations,
 * InstalledRelationScopeOperations) -> RelationCompilerPort`.
 *
 * Establishes one request-local K2 relation port for the exact installed root. Missing projects,
 * unavailable publications, and moved generations remain [RelationCompilerRejection] data. Live
 * project, PSI, scope, native search, and K2 values are obtained and consumed within each call.
 */
fun installedIntellijRelationCompiler(
    root: CanonicalWorkspaceRoot,
    workspaces: WorkspaceInspectionOperations,
    scopes: InstalledRelationScopeOperations,
): RelationCompilerPort {
    val adapter = IntellijRelationCompilerAdapter()
    return RelationCompilerPort { request ->
        val project = exactProject(root) ?: return@RelationCompilerPort unavailable()
        val current = (workspaces.inspect() as? WorkspaceRuntimeState.Ready)
                          ?.workspace
                          ?.readLease
                      ?: return@RelationCompilerPort unavailable()
        adapter.read(project, current, request, scopes.compile(current))
    }
}

private fun exactProject(root: CanonicalWorkspaceRoot): Project? =
    ProjectManager.getInstance().openProjects.singleOrNull { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?.toString() == root.value
    }

private fun unavailable(): RelationCompilation = RelationCompilation.Rejected(
    RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
)
