package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ManagementRoot
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface ManagedWorkspaceStatus {
    @Serializable
    @SerialName("RECORDED")
    data class Recorded(
        val id: String,
        val workspace: String,
        val runtimeArchiveSha256: Observation<String>,
        val host: Observation<LoadedApplicationHost>,
        val dependencySharing: WorkspaceDependencySharing,
        val gradleHome: String,
        val savedDependencyConfiguration: Observation<WorkspaceDependencyMatch>,
        val seed: Observation<WorkspaceSeedRecord>,
        val hostStages: PassiveHostStages,
    ) : ManagedWorkspaceStatus

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val entry: String, val failure: ContainerFailure) : ManagedWorkspaceStatus
}

@Serializable
internal enum class WorkspaceDependencyMatch {
    MATCHES_CREATION,
    DIFFERS_FROM_CREATION,
}

/** No leases, trust writes, generation repair, or lifecycle requests belong to status. */
internal fun readWorkspaceStatuses(
    root: Path,
    scope: WorkspaceScope,
    directory: Path,
): Observation<List<ManagedWorkspaceStatus>> {
    val statuses = containerFiles {
        when (val store = containerStore(ManagementRoot.of(root).share.workspaceStore)) {
            is Refinement.Rejected -> store
            is Refinement.Refined ->
                when (val entries = scopedWorkspaces(store.value, scope, directory)) {
                    is Refinement.Rejected -> entries
                    is Refinement.Refined ->
                        Refinement.Refined(
                            entries.value.map {
                                workspaceStatus(store.value, it, ManagementRoot.of(root).management.installation)
                            }
                        )
                }
        }
    }
    return when (statuses) {
        is Refinement.Rejected -> Observation.unavailable(statuses.failure.name)
        is Refinement.Refined -> Observation.verified(statuses.value)
    }
}

private fun workspaceStatus(
    store: ContainerStore,
    entry: ContainerStoreEntry,
    installation: Path,
): ManagedWorkspaceStatus =
    when (entry) {
        is ContainerStoreEntry.Rejected ->
            ManagedWorkspaceStatus.Rejected(entry.directory.fileName.toString(), entry.failure)
        is ContainerStoreEntry.Admitted ->
            when (val generation = store.admitGeneration(entry.workspace)) {
                is Refinement.Rejected ->
                    ManagedWorkspaceStatus.Recorded(
                        entry.workspace.id.text,
                        entry.workspace.workspace.toString(),
                        Observation.unavailable(generation.failure.name),
                        Observation.unavailable(generation.failure.name),
                        entry.workspace.dependencySharing,
                        entry.workspace.gradleHome.toString(),
                        savedDependencyMatch(store, entry.workspace, installation),
                        Observation.unavailable(generation.failure.name),
                        PassiveHostStages.at(PassiveHostStep.GENERATION),
                    )
                is Refinement.Refined -> {
                    val host = readWorkspaceApplicationHost(generation.value)
                    ManagedWorkspaceStatus.Recorded(
                        entry.workspace.id.text,
                        entry.workspace.workspace.toString(),
                        Observation.verified(generation.value.runtime.archiveSha256),
                        host.observation,
                        entry.workspace.dependencySharing,
                        entry.workspace.gradleHome.toString(),
                        savedDependencyMatch(store, entry.workspace, installation),
                        workspaceSeedStatus(generation.value),
                        PassiveHostStages.at(host.stage),
                    )
                }
            }
    }

private fun savedDependencyMatch(
    store: ContainerStore,
    workspace: AdmittedWorkspace,
    installation: Path,
): Observation<WorkspaceDependencyMatch> {
    val compared = containerFiles {
        val resolved =
            when (val selected = containerPreparationConfiguration(installation, workspace.workspace, emptyMap())) {
                is Refinement.Rejected -> return@containerFiles selected
                is Refinement.Refined -> selected.value
            }
        val policy = resolved.dependencySharingFor(workspace.dependencySharing)
        val explicit = resolved.launchEnvironment().variables["GRADLE_USER_HOME"]
        val gradle =
            if (explicit != null) Path.of(explicit).toRealPath()
            else store.defaultGradleHome(workspace.directory, policy)
        Refinement.Refined(
            if (policy == workspace.dependencySharing && gradle == workspace.gradleHome)
                WorkspaceDependencyMatch.MATCHES_CREATION
            else WorkspaceDependencyMatch.DIFFERS_FROM_CREATION
        )
    }
    return when (compared) {
        is Refinement.Rejected -> Observation.unavailable(compared.failure.name)
        is Refinement.Refined -> Observation.verified(compared.value)
    }
}

private fun workspaceSeedStatus(container: AdmittedContainer): Observation<WorkspaceSeedRecord> =
    when (
        val read = containerFiles {
            readContainerRecord<WorkspaceSeedRecord>(container.state.resolve("workspace-seed.json"))
        }
    ) {
        is Refinement.Rejected -> Observation.unavailable(read.failure.name)
        is Refinement.Refined -> {
            val valid =
                read.value.schemaVersion == 1 &&
                    when (val result = read.value.result) {
                        is WorkspaceSeedResult.Seeded -> ContainerId.parse(result.donor) is Refinement.Refined
                        WorkspaceSeedResult.NotRequested,
                        WorkspaceSeedResult.NoEligibleDonor,
                        WorkspaceSeedResult.DonorsBusy,
                        WorkspaceSeedResult.UnsupportedFilesystem,
                        is WorkspaceSeedResult.CloneFailed,
                        is WorkspaceSeedResult.SelectionRejected,
                        is WorkspaceSeedResult.CleanupRejected -> true
                    }
            if (valid) Observation.verified(read.value)
            else Observation.unavailable(ContainerFailure.INVALID_RECORD.name)
        }
    }
