package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.workspace.contract.CanonicalSemanticProjectRoot
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.Path

/**
 * Proof transition: `(CanonicalSemanticProjectRoot, WorkspaceInspectionOperations) ->
 * DiagnosticCompilerPort`.
 *
 * Establishes one request-local K2 diagnostic port for the exact installed root. Missing projects
 * and unavailable current publications remain [DiagnosticCompilerRejection] data. Live project,
 * VFS, PSI, and K2 values are obtained and consumed only inside each compiler call.
 */
fun installedIntellijDiagnosticCompiler(
    projectRoot: CanonicalSemanticProjectRoot,
    workspaces: WorkspaceInspectionOperations,
): DiagnosticCompilerPort {
    val adapter = IntellijDiagnosticCompilerAdapter()
    return DiagnosticCompilerPort { scope ->
        val project = exactProject(projectRoot) ?: return@DiagnosticCompilerPort unavailable()
        val current = (workspaces.inspect() as? WorkspaceRuntimeState.Ready)
                          ?.workspace
                          ?.readLease
                      ?: return@DiagnosticCompilerPort unavailable()
        adapter.read(project, current, scope)
    }
}

private fun exactProject(root: CanonicalSemanticProjectRoot): Project? =
    ProjectManager.getInstance().openProjects.singleOrNull { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?.toString() == root.value
    }

private fun unavailable(): DiagnosticCompilation = DiagnosticCompilation.Rejected(
    DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
)
