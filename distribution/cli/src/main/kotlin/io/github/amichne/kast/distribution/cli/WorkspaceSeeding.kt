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
    recordWorkspaceSeed(staged.staging, result)
}

/**
 * A rebuilt generation starts from the index storage of its own retired one, under any sharing policy. Retirement
 * proves that the exact Host stopped, and the preparation lock excludes another reader.
 */
internal fun seedGeneration(
    previous: AdmittedContainer,
    runtime: AdmittedContainerRuntime,
    staging: Path,
    clone: (Path, Path) -> WorkspaceSeedClone,
): Refinement<Unit, ContainerFailure> = containerFiles {
    val donor = containerFiles { admitSeedStorage(previous, runtime) }
    val result =
        when (donor) {
            is Refinement.Rejected -> WorkspaceSeedResult.NoEligibleDonor
            is Refinement.Refined ->
                if (ideStopped(previous)) cloneSeed(previous, staging, clone) else WorkspaceSeedResult.NoEligibleDonor
        }
    recordWorkspaceSeed(staging, result)
}

private fun recordWorkspaceSeed(staging: Path, result: WorkspaceSeedResult): Refinement<Unit, ContainerFailure> {
    when (result) {
        is WorkspaceSeedResult.CloneFailed,
        WorkspaceSeedResult.UnsupportedFilesystem -> {
            val cleanup = containerFiles { discardWorkspaceSeed(staging.resolve("state/system")) }
            when (cleanup) {
                is Refinement.Rejected -> {
                    System.err.println(
                        containerJson.encodeToString(
                            WorkspaceSeedProgress(WorkspaceSeedResult.CleanupRejected(cleanup.failure))
                        )
                    )
                    return cleanup
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
    writeContainerRecord(staging.resolve("state/workspace-seed.json"), WorkspaceSeedRecord(result))
    System.err.println(containerJson.encodeToString(WorkspaceSeedProgress(result)))
    return Refinement.Refined(Unit)
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
            .filter { it.gradleHome == staged.creation.gradleHome }
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
    if (
        !ideStopped(donor) ||
            probe.inspect(donor.runtime.home, donor.state, donor.home) != WorkspaceSeedEndpoint.HOST_UNAVAILABLE
    )
        return Refinement.Refined(WorkspaceSeedResult.NoEligibleDonor)
    return Refinement.Refined(cloneSeed(donor, staged.staging, clone))
}

private fun cloneSeed(
    donor: AdmittedContainer,
    staging: Path,
    clone: (Path, Path) -> WorkspaceSeedClone,
): WorkspaceSeedResult =
    when (val copied = clone(donor.state.resolve("system"), staging.resolve("state/system"))) {
        WorkspaceSeedClone.Completed -> WorkspaceSeedResult.Seeded(donor.id.text)
        WorkspaceSeedClone.Unsupported -> WorkspaceSeedResult.UnsupportedFilesystem
        is WorkspaceSeedClone.Failed -> WorkspaceSeedResult.CloneFailed(copied.stage)
    }

/** The IDE removes its configuration lock after it flushes its storage. An unknown layout proves nothing. */
private fun ideStopped(donor: AdmittedContainer): Boolean =
    when (val launch = SelectedIdeInstallation.resolve(donor.runtime.home)) {
        is SelectedIdeLaunch.Unavailable -> false
        is SelectedIdeLaunch.Resolved ->
            when (val plugins = SelectedIdeInstallation.pluginRoot(launch, donor.home)) {
                is Refinement.Rejected -> false
                is Refinement.Refined -> Files.notExists(plugins.value.value.parent.resolve(".lock"), NOFOLLOW_LINKS)
            }
    }

private fun seedCandidate(
    store: ContainerStore,
    owned: AdmittedWorkspace,
    runtime: AdmittedContainerRuntime,
): Refinement<WorkspaceSeedCandidate, ContainerFailure> {
    // Reject a pending removal before admitting runtime files or touching a lease.
    if (!Files.notExists(store.containers.resolve(".removing-${owned.id.text}.json"), NOFOLLOW_LINKS))
        return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    return when (val donor = store.admitGeneration(owned)) {
        is Refinement.Rejected -> donor
        is Refinement.Refined -> admitSeedStorage(donor.value, runtime)
    }
}

/**
 * Index and file system storage belongs to one IDE distribution. Kast contributes no index, gist, or file type, so its
 * own build does not change that storage.
 */
private fun admitSeedStorage(
    donor: AdmittedContainer,
    runtime: AdmittedContainerRuntime,
): Refinement<WorkspaceSeedCandidate, ContainerFailure> {
    val source = donor.runtime.manifest
    val target = runtime.manifest
    if (
        source.distributionSha256 != target.distributionSha256 ||
            source.platformBuild != target.platformBuild ||
            source.platform != target.platform
    )
        return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
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
    val system = donor.state.resolve("system")
    if (
        admitPrivateContainerDirectory(system) is Refinement.Rejected ||
            !WORKSPACE_SEED_STORAGE.all { Files.isDirectory(system.resolve(it), NOFOLLOW_LINKS) }
    )
        return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    return Refinement.Refined(WorkspaceSeedCandidate(donor, Files.getLastModifiedTime(path, NOFOLLOW_LINKS)))
}
