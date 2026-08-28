package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure

class HostedTopologyPorts private constructor(
    val candidates: TopologyCandidateEnumerator,
    val fileExtractor: TopologyFileExtractor,
) {
    companion object {
        internal fun retained(
            candidates: TopologyCandidateEnumerator,
            fileExtractor: TopologyFileExtractor,
        ): HostedTopologyPorts = HostedTopologyPorts(candidates, fileExtractor)
    }
}

sealed interface HostedTopologyAdmission {
    data class Admitted(val ports: HostedTopologyPorts) : HostedTopologyAdmission
    data class Rejected(val failure: HostedProjectAdmissionFailure) : HostedTopologyAdmission
}

/**
 * Admits the already-open exact Project once, then retains it only inside the topology effect.
 * No project discovery, lookup, callback, or raw Project accessor exists on the returned ports.
 */
fun admitHostedIntellijTopologyPorts(
    project: Project,
    root: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
    workspaces: WorkspaceInspectionOperations,
): HostedTopologyAdmission {
    when (val admission = AdmittedIdeProject.admit(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        is ExistingProjectAdmission.Admitted -> Unit
        is ExistingProjectAdmission.Rejected -> return HostedTopologyAdmission.Rejected(
            HostedProjectAdmissionFailure.ProjectRejected(admission.failure),
        )
    }
    val adapter = IntellijTopologyFileExtractor()
    val extractor = TopologyFileExtractor { request ->
        if (project.isDisposed) {
            return@TopologyFileExtractor topologyUnavailable()
        }
        val current = (workspaces.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
            ?: return@TopologyFileExtractor topologyUnavailable()
        adapter.extract(project, current, request)
    }
    return HostedTopologyAdmission.Admitted(
        HostedTopologyPorts.retained(AdmittedSourceRootEnumerator(), extractor),
    )
}

private fun topologyUnavailable(): TopologyFileExtraction = TopologyFileExtraction.Failed(
    TopologyExtractionFailure.PROJECT_UNAVAILABLE,
)
