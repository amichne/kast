package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Endpoint observation alone proves neither process exit nor flushed storage. */
enum class WorkspaceSeedEndpoint {
    HOST_UNAVAILABLE,
    UNPROVEN,
}

/** The CLI supplies its existing lifecycle transport; distribution owns donor selection and the lease. */
fun interface WorkspaceSeedHostProbe {
    fun inspect(runtime: Path, state: Path, home: Path): WorkspaceSeedEndpoint
}

internal fun seedWorkspace(
    store: ContainerStore,
    staged: StagedContainerCreation,
    probe: WorkspaceSeedHostProbe,
    clone: (Path, Path) -> WorkspaceSeedClone = ::cloneWorkspaceSystem,
): Refinement<Unit, ContainerFailure> = containerFiles {
    val result =
        when (staged.creation.dependencySharing) {
            WorkspaceDependencySharing.PRIVATE -> WorkspaceSeedResult.NotRequested
            WorkspaceDependencySharing.SHARED -> selectWorkspaceSeed(store, staged, probe, clone)
        }
    when (result) {
        is WorkspaceSeedResult.CloneFailed,
        WorkspaceSeedResult.UnsupportedFilesystem -> {
            val cleanup = containerFiles { discardWorkspaceSeed(staged.staging.resolve("state/system")) }
            when (cleanup) {
                is Refinement.Rejected -> {
                    System.err.println(
                        containerJson.encodeToString(
                            WorkspaceSeedProgress(WorkspaceSeedResult.CleanupRejected(cleanup.failure))
                        )
                    )
                    return@containerFiles cleanup
                }
                is Refinement.Refined -> Unit
            }
        }
        is WorkspaceSeedResult.Seeded,
        WorkspaceSeedResult.NotRequested,
        WorkspaceSeedResult.NoEligibleDonor,
        WorkspaceSeedResult.DonorsBusy,
        is WorkspaceSeedResult.SelectionRejected,
        is WorkspaceSeedResult.CleanupRejected -> Unit
    }
    // Persist only after successful cleanup. A partial clone can never reach publication.
    writeContainerRecord(staged.staging.resolve("state/workspace-seed.json"), WorkspaceSeedRecord(result))
    System.err.println(containerJson.encodeToString(WorkspaceSeedProgress(result)))
    Refinement.Refined(Unit)
}

@Serializable
internal sealed interface WorkspaceSeedResult {
    @Serializable @SerialName("NOT_REQUESTED") data object NotRequested : WorkspaceSeedResult

    @Serializable @SerialName("SEEDED") data class Seeded(val donor: String) : WorkspaceSeedResult

    @Serializable @SerialName("NO_ELIGIBLE_DONOR") data object NoEligibleDonor : WorkspaceSeedResult

    @Serializable @SerialName("DONORS_BUSY") data object DonorsBusy : WorkspaceSeedResult

    @Serializable @SerialName("UNSUPPORTED_FILESYSTEM") data object UnsupportedFilesystem : WorkspaceSeedResult

    @Serializable
    @SerialName("CLONE_FAILED")
    data class CloneFailed(val stage: WorkspaceSeedCloneStage) : WorkspaceSeedResult

    @Serializable
    @SerialName("SELECTION_REJECTED")
    data class SelectionRejected(val failure: ContainerFailure) : WorkspaceSeedResult

    @Serializable
    @SerialName("CLEANUP_REJECTED")
    data class CleanupRejected(val failure: ContainerFailure) : WorkspaceSeedResult
}

@Serializable
internal data class WorkspaceSeedRecord(val result: WorkspaceSeedResult, @Required val schemaVersion: Int = 1)

@Serializable
private data class WorkspaceSeedProgress(val result: WorkspaceSeedResult, val event: String = "kast_workspace_seed")

private class WorkspaceSeedCandidate(val container: AdmittedContainer, val modified: FileTime)

private fun selectWorkspaceSeed(
    store: ContainerStore,
    staged: StagedContainerCreation,
    probe: WorkspaceSeedHostProbe,
    clone: (Path, Path) -> WorkspaceSeedClone,
): WorkspaceSeedResult {
    val entries =
        when (val read = store.enumerate()) {
            is Refinement.Rejected -> return WorkspaceSeedResult.SelectionRejected(read.failure)
            is Refinement.Refined -> read.value
        }
    val compatible =
        entries
            .filterIsInstance<ContainerStoreEntry.Admitted>()
            .map { it.workspace }
            .filter {
                it.generation == ContainerGenerationSource.Current && it.gradleHome == staged.creation.gradleHome
            }
    val candidates = seedCandidates(store, compatible, staged.container.runtime)
    var busy = false
    for (candidate in candidates) {
        val selected =
            ContainerQueryLease.whileIdle(candidate.container) {
                containerFiles { seedFromStoppedDonor(store, candidate.container.owned, staged, probe, clone) }
            }
        when (selected) {
            is Refinement.Refined -> if (selected.value != WorkspaceSeedResult.NoEligibleDonor) return selected.value
            is Refinement.Rejected -> busy = busy || selected.failure == ContainerFailure.BUSY
        }
    }
    return if (busy) WorkspaceSeedResult.DonorsBusy else WorkspaceSeedResult.NoEligibleDonor
}

private fun seedCandidates(
    store: ContainerStore,
    compatible: List<AdmittedWorkspace>,
    runtime: AdmittedContainerRuntime,
): List<WorkspaceSeedCandidate> = buildList {
    for (owned in compatible) {
        when (val read = containerFiles { seedCandidate(store, owned, runtime) }) {
            is Refinement.Rejected -> Unit
            is Refinement.Refined -> add(read.value)
        }
    }
}
    .sortedWith(compareByDescending<WorkspaceSeedCandidate> { it.modified }.thenBy { it.container.id.text })

private fun seedFromStoppedDonor(
    store: ContainerStore,
    owned: AdmittedWorkspace,
    staged: StagedContainerCreation,
    probe: WorkspaceSeedHostProbe,
    clone: (Path, Path) -> WorkspaceSeedClone,
): Refinement<WorkspaceSeedResult, ContainerFailure> {
    // Lease acquisition re-admits durable ownership; re-read the generation and exit record too.
    val donor =
        when (val current = seedCandidate(store, owned, staged.container.runtime)) {
            is Refinement.Rejected -> return current
            is Refinement.Refined -> current.value.container
        }
    val launch =
        when (val selected = SelectedIdeInstallation.resolve(donor.runtime.home)) {
            is SelectedIdeLaunch.Unavailable -> return Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE)
            is SelectedIdeLaunch.Resolved -> selected
        }
    val plugins =
        when (val selected = SelectedIdeInstallation.pluginRoot(launch, donor.home)) {
            is Refinement.Rejected -> return Refinement.Refined(WorkspaceSeedResult.NoEligibleDonor)
            is Refinement.Refined -> selected.value
        }
    if (
        !Files.notExists(plugins.value.parent.resolve(".lock"), NOFOLLOW_LINKS) ||
            probe.inspect(donor.runtime.home, donor.state, donor.home) != WorkspaceSeedEndpoint.HOST_UNAVAILABLE
    )
        return Refinement.Refined(WorkspaceSeedResult.NoEligibleDonor)
    return Refinement.Refined(
        when (val copied = clone(donor.state.resolve("system"), staged.staging.resolve("state/system"))) {
            WorkspaceSeedClone.Completed -> WorkspaceSeedResult.Seeded(donor.id.text)
            WorkspaceSeedClone.Unsupported -> WorkspaceSeedResult.UnsupportedFilesystem
            is WorkspaceSeedClone.Failed -> WorkspaceSeedResult.CloneFailed(copied.stage)
        }
    )
}

private fun seedCandidate(
    store: ContainerStore,
    owned: AdmittedWorkspace,
    runtime: AdmittedContainerRuntime,
): Refinement<WorkspaceSeedCandidate, ContainerFailure> {
    // Reject the cheap runtime binding before admitting runtime files or touching a lease.
    val generation =
        when (val read = readContainerRecord<ContainerGenerationRecord>(owned.directory.resolve("generation.json"))) {
            is Refinement.Rejected -> return read
            is Refinement.Refined -> read.value
        }
    if (
        generation.archiveSha256 != runtime.archiveSha256 ||
            !Files.notExists(store.containers.resolve(".removing-${owned.id.text}.json"), NOFOLLOW_LINKS)
    )
        return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    val donor =
        when (val read = store.admitGeneration(owned)) {
            is Refinement.Rejected -> return read
            is Refinement.Refined -> read.value
        }
    val path = HostExitRecord.path(donor.state)
    val record =
        when (val read = readContainerRecord<HostExitRecord>(path)) {
            is Refinement.Rejected -> return read
            is Refinement.Refined -> read.value
        }
    if (
        record.schemaVersion != HostExitRecord.SCHEMA_VERSION ||
            record.index != HostExitIndexState.SMART ||
            UUID.fromString(record.hostLifetime).toString() != record.hostLifetime
    )
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    if (admitPrivateContainerDirectory(donor.state.resolve("system")) is Refinement.Rejected)
        return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    return Refinement.Refined(WorkspaceSeedCandidate(donor, Files.getLastModifiedTime(path, NOFOLLOW_LINKS)))
}
