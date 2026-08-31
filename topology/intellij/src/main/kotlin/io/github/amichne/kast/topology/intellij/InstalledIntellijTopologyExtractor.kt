package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.InvalidPathException
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
            is ExactTopologyProjectResolution.Found -> lookup.project
            is ExactTopologyProjectResolution.Rejected -> return@TopologyFileExtractor failed(
                request.file,
                lookup.failure,
            )
        }
        val current = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return@TopologyFileExtractor unavailable(request.file)
        }
        adapter.extract(project, current, request)
    }
}

/** Closed exact-root selection from live IntelliJ project metadata. */
internal sealed interface ExactTopologyProjectResolution {
    data class Found(val project: Project) : ExactTopologyProjectResolution

    data class Rejected(
        val failure: TopologyExtractionFailure,
    ) : ExactTopologyProjectResolution
}

/**
 * Proof transition: `CanonicalWorkspaceRoot -> ExactTopologyProjectResolution`.
 *
 * Found establishes the only live IntelliJ project for the canonical root. Rejected closes absent,
 * ambiguous, and malformed platform project metadata. Raw platform projects are observed only at
 * this adapter.
 */
private fun exactProject(root: CanonicalWorkspaceRoot): ExactTopologyProjectResolution =
    resolveExactTopologyProject(root, ProjectManager.getInstance().openProjects.asIterable())

/**
 * Proof transition: `(CanonicalWorkspaceRoot, Iterable<Project>) ->
 * ExactTopologyProjectResolution`.
 *
 * [ExactTopologyProjectResolution.Found] establishes exactly one live IntelliJ project whose
 * well-formed normalized absolute base path equals the canonical workspace root.
 * [ExactTopologyProjectResolution.Rejected] closes missing, ambiguous, malformed, and inaccessible
 * platform paths as [TopologyExtractionFailure.PROJECT_UNAVAILABLE]. Raw `Project.basePath` text
 * may enter only from installed IntelliJ project discovery.
 */
internal fun resolveExactTopologyProject(
    root: CanonicalWorkspaceRoot,
    projects: Iterable<Project>,
): ExactTopologyProjectResolution {
    val matches = mutableListOf<Project>()
    for (project in projects) {
        if (project.isDisposed) continue
        val rawPath = project.basePath ?: continue
        val path = try {
            Path.of(rawPath).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return projectUnavailable()
        } catch (_: SecurityException) {
            return projectUnavailable()
        }
        if (path.toString() == root.value) matches += project
    }
    return if (matches.size == 1) ExactTopologyProjectResolution.Found(matches.single())
    else projectUnavailable()
}

private fun projectUnavailable(): ExactTopologyProjectResolution.Rejected =
    ExactTopologyProjectResolution.Rejected(TopologyExtractionFailure.PROJECT_UNAVAILABLE)

private fun unavailable(
    file: io.github.amichne.kast.topology.contract.TopologySourceFile,
): TopologyFileExtraction = TopologyFileExtraction.Failed(
    file,
    TopologyExtractionFailure.PROJECT_UNAVAILABLE,
)

private fun failed(
    file: io.github.amichne.kast.topology.contract.TopologySourceFile,
    failure: TopologyExtractionFailure,
): TopologyFileExtraction = TopologyFileExtraction.Failed(file, failure)
