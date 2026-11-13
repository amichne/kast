package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Explicit uninstall can recover a damaged record, never an unproved live Host or mutation. */
internal fun captureRejectedWorkspaceRemoval(
    store: ContainerStore,
    entry: ContainerStoreEntry.Rejected,
    observe: (UninstallCleanupObservation) -> Unit,
): ManagedWorkspaceRemoval {
    rejectWorkspaceEntry(entry.directory, entry.failure)
    val id = requireWorkspaceRemoval(ContainerId.parse(entry.directory.fileName.toString()))
    if (entry.directory != store.containers.resolve(id.text))
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
    return requireWorkspaceRemoval(
        ContainerQueryLease.whileReclaiming(store, id) {
            containerFiles {
                requireWorkspaceRemoval(admitPrivateContainerDirectory(entry.directory))
                withWorkspaceRemovalLock(entry.directory) {
                    captureInactiveWorkspace(entry.directory, id, observe)
                }
            }
        }
    )
}

/** Legacy readers used an interior lock; current readers use the exterior lease held by the caller. */
internal fun <T> withWorkspaceRemovalLock(directory: Path, action: () -> T): Refinement<T, ContainerFailure> {
    requireWorkspaceRemoval(admitPrivateContainerDirectory(directory))
    val path = directory.resolve("queries.lock")
    if (Files.exists(path, NOFOLLOW_LINKS)) {
        if (
            !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                Files.size(path) != 0L ||
                Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(directory, NOFOLLOW_LINKS)
        )
            return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    }
    return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
        val lock =
            try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
        if (lock == null) Refinement.Rejected(ContainerFailure.BUSY) else lock.use { Refinement.Refined(action()) }
    }
}

private fun captureInactiveWorkspace(
    directory: Path,
    id: ContainerId,
    observe: (UninstallCleanupObservation) -> Unit,
): ManagedWorkspaceRemoval {
    val record = directory.resolve("container.json")
    requireWorkspaceRemoval(
        if (!Files.isRegularFile(record, NOFOLLOW_LINKS) || Files.size(record) > CONTAINER_RECORD_BYTES)
            Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        else Refinement.Refined(Unit)
    )
    val proof = ManagedWorkspaceRemoval(id.text, uninstallFilesystemIdentity(directory), sha256(record))
    val paths = Files.walk(directory).use { it.limit(MAXIMUM_PROFILE_FILES + 1).toList() }
    if (paths.size > MAXIMUM_PROFILE_FILES)
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.CAPACITY_EXCEEDED.name)
    if (
        paths.any { it.fileName.toString() == ".lock" || uninstallFilesystemIdentity(it).owner != proof.identity.owner }
    )
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
    for (home in workspaceProfileHomes(directory)) {
        requireWorkspaceRemoval(admitRemovalProfile(home))
        cleanupUninstalledUserState(home, observe)
    }
    return proof
}

/** A damaged workspace record cannot nominate a profile outside its private HOME. */
private fun admitRemovalProfile(home: Path): Refinement<Unit, ContainerFailure> {
    requireWorkspaceRemoval(admitPrivateContainerDirectory(home))
    val profile = home.parent.parent
    val launch =
        requireWorkspaceRemoval(readContainerRecord<SelectedIdeLaunch>(profile.resolve("config/selected-ide.json")))
    return when (launch) {
        is SelectedIdeLaunch.Unavailable -> Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        is SelectedIdeLaunch.Resolved ->
            when (val target = launch.hostPluginTarget) {
                InstalledHostPluginTarget.Unrecorded -> Refinement.Rejected(ContainerFailure.INVALID_RECORD)
                is InstalledHostPluginTarget.Recorded ->
                    when (SelectedIdeInstallation.recordedPluginRoot(target, home)) {
                        is Refinement.Rejected -> Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
                        is Refinement.Refined -> Refinement.Refined(Unit)
                    }
            }
    }
}
