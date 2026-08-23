package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.Path

/**
 * Proof transition: `(CanonicalWorkspaceRoot, WorkspaceInspectionOperations) ->
 * TopologyFileExtractor`.
 *
 * Establishes one exact-root installed K2 boundary. Project lookup and current workspace
 * observation occur for each explicit extraction call; no foreground IDE control or autostart is
 * performed and no live platform value is retained.
 */
fun installedIntellijTopologyExtractor(
    root: CanonicalWorkspaceRoot,
    workspaces: WorkspaceInspectionOperations,
): TopologyFileExtractor {
    val adapter = IntellijTopologyFileExtractor()
    return TopologyFileExtractor { request ->
        val project = when (val lookup = exactProject(root)) {
            is ExactTopologyProjectLookup.Found -> lookup.project
            ExactTopologyProjectLookup.Unavailable -> return@TopologyFileExtractor unavailable()
        }
        val current = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return@TopologyFileExtractor unavailable()
        }
        adapter.extract(project, current, request)
    }
}

private sealed interface ExactTopologyProjectLookup {
    data class Found(val project: Project) : ExactTopologyProjectLookup
    data object Unavailable : ExactTopologyProjectLookup
}

/**
 * Proof transition: `CanonicalWorkspaceRoot -> ExactTopologyProjectLookup`.
 *
 * Found establishes the only live IntelliJ project for the canonical root. Unavailable is the
 * closed absence or ambiguity state. Raw platform projects are observed only at this adapter.
 */
private fun exactProject(root: CanonicalWorkspaceRoot): ExactTopologyProjectLookup {
    val matches = ProjectManager.getInstance().openProjects.filter { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?.toString() == root.value
    }
    return if (matches.size == 1) ExactTopologyProjectLookup.Found(matches.single())
    else ExactTopologyProjectLookup.Unavailable
}

private fun unavailable(): TopologyFileExtraction = TopologyFileExtraction.Failed(
    TopologyExtractionFailure.PROJECT_UNAVAILABLE,
)
