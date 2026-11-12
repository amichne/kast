package io.github.amichne.kast.distribution.cli

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
    ) : ManagedWorkspaceStatus

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val entry: String, val failure: ContainerFailure) : ManagedWorkspaceStatus
}

/** No leases, trust writes, generation repair, or lifecycle requests belong to status. */
internal fun readWorkspaceStatuses(
    root: Path,
    scope: WorkspaceScope,
    directory: Path,
): Observation<List<ManagedWorkspaceStatus>> {
    val statuses = containerFiles {
        when (val store = containerStore(root.resolveSibling("kast-workspaces"))) {
            is Refinement.Rejected -> store
            is Refinement.Refined ->
                when (val entries = scopedWorkspaces(store.value, scope, directory)) {
                    is Refinement.Rejected -> entries
                    is Refinement.Refined -> Refinement.Refined(entries.value.map { workspaceStatus(store.value, it) })
                }
        }
    }
    return when (statuses) {
        is Refinement.Rejected -> Observation.unavailable(statuses.failure.name)
        is Refinement.Refined -> Observation.verified(statuses.value)
    }
}

private fun workspaceStatus(store: ContainerStore, entry: ContainerStoreEntry): ManagedWorkspaceStatus =
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
                    )
                is Refinement.Refined ->
                    ManagedWorkspaceStatus.Recorded(
                        entry.workspace.id.text,
                        entry.workspace.workspace.toString(),
                        Observation.verified(generation.value.runtime.archiveSha256),
                        readWorkspaceApplicationHost(generation.value),
                    )
            }
    }
