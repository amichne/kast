package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.WorkspaceConfigurationConflict
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Compares the recorded dependency policy without loading a Host or acquiring a query lease. */
@Serializable
internal sealed interface WorkspaceSharingInspection {
    @Serializable
    @SerialName("NEW_WORKSPACE")
    data class Creation(val policy: WorkspaceDependencySharing) : WorkspaceSharingInspection

    @Serializable
    @SerialName("REUSE_RECORDED")
    data class Reuse(val policy: WorkspaceDependencySharing) : WorkspaceSharingInspection

    @Serializable
    @SerialName("CONFIGURATION_CONFLICT")
    data class Conflict(val conflict: WorkspaceConfigurationConflict.DependencySharing) : WorkspaceSharingInspection

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val reason: ContainerFailure) : WorkspaceSharingInspection

    fun description(): String =
        when (this) {
            is Creation -> "Dependency policy at creation: ${policy.name}"
            is Reuse -> "Dependency policy on reconnect: ${policy.name} (recorded workspace)"
            is Conflict ->
                "CONFIGURATION_CONFLICT: ${conflict.source} selects ${conflict.requested}. " + conflict.recovery()
            is Unavailable -> "Recorded dependency policy unavailable: ${reason.name}"
        }
}

internal fun inspectWorkspaceSharing(
    installation: Path,
    workspace: CanonicalRoot,
    configuration: ResolvedKastConfiguration,
    environment: Map<String, String>,
): WorkspaceSharingInspection {
    val recorded =
        when (val store = containerStore(installation.parent.resolveSibling("kast-workspaces"))) {
            is Refinement.Rejected -> return WorkspaceSharingInspection.Unavailable(store.failure)
            is Refinement.Refined -> store.value.workspace(workspace.path)
        }
    return when (recorded) {
        is Refinement.Rejected ->
            if (recorded.failure == ContainerFailure.NOT_FOUND)
                WorkspaceSharingInspection.Creation(configuration.workspaceDependencySharing)
            else WorkspaceSharingInspection.Unavailable(recorded.failure)
        is Refinement.Refined ->
            when (val policy = admitWorkspaceSharing(installation, recorded.value, configuration, environment)) {
                is Refinement.Refined -> WorkspaceSharingInspection.Reuse(policy.value)
                is Refinement.Rejected -> WorkspaceSharingInspection.Conflict(policy.failure)
            }
    }
}
