package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable

/** The existing uninstall journal retains this binding until every selected profile is gone. */
@Serializable
internal data class ManagedWorkspaceRemoval(
    val id: String,
    val identity: InstallationFilesystemIdentity,
    val recordSha256: String,
)

internal fun uninstallWorkspaceStore(root: Path): ContainerStore =
    requireWorkspaceRemoval(containerStore(root.resolveSibling("kast-workspaces")))

internal fun prepareProductWorkspaceRemoval(
    root: Path,
    home: Path,
    observe: (UninstallCleanupObservation) -> Unit,
): List<ManagedWorkspaceRemoval> {
    if (Files.exists(uninstallJournalPath(root), NOFOLLOW_LINKS)) return readUninstallRetirement(root).workspaceRemovals
    requireWorkspaceRemoval(retireManagedProduct(root, home, System.getenv()))
    return captureWorkspaceRemovals(uninstallWorkspaceStore(root), observe)
}

/** Immutable payload removal has already emptied runtimes; only known empty locks remain here. */
internal fun cleanupRemovedWorkspaceStore(root: Path) {
    val store = uninstallWorkspaceStore(root)
    if (Files.notExists(store.root, NOFOLLOW_LINKS)) return
    requireWorkspaceRemoval(admitPrivateContainerDirectory(store.root))
    Files.newDirectoryStream(store.root).use { entries ->
        if (entries.any { it.fileName.toString() !in setOf("locks", "container.lock", "runtimes") })
            throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
    }
    removeWorkspaceQueryLocks(store.root.resolve("locks"))
    if (Files.exists(store.runtimes, NOFOLLOW_LINKS)) {
        requireWorkspaceRemoval(admitPrivateContainerDirectory(store.runtimes))
        Files.delete(store.runtimes)
    }
    removeWorkspaceLock(store.root.resolve("container.lock"))
    Files.delete(store.root)
}

private fun removeWorkspaceQueryLocks(locks: Path) {
    if (Files.notExists(locks, NOFOLLOW_LINKS)) return
    requireWorkspaceRemoval(admitPrivateContainerDirectory(locks))
    Files.newDirectoryStream(locks).use { entries ->
        for (path in entries) {
            val name = path.fileName.toString()
            if (!name.endsWith(".queries.lock"))
                throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
            requireWorkspaceRemoval(ContainerId.parse(name.removeSuffix(".queries.lock")))
            removeWorkspaceLock(path)
        }
    }
    Files.delete(locks)
}

private fun removeWorkspaceLock(path: Path) {
    if (Files.notExists(path, NOFOLLOW_LINKS)) return
    if (
        !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            Files.size(path) != 0L ||
            uninstallFilesystemIdentity(path).owner != com.sun.security.auth.module.UnixSystem().uid
    )
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
    Files.delete(path)
}

/** Called behind the product fence and exterior store-removal lease, after all Hosts retired. */
internal fun captureWorkspaceRemovals(
    store: ContainerStore,
    observe: (UninstallCleanupObservation) -> Unit,
): List<ManagedWorkspaceRemoval> {
    val entries = requireWorkspaceRemoval(store.enumerate())
    val removals = mutableListOf<ManagedWorkspaceRemoval>()
    for (entry in entries) {
        val workspace =
            when (entry) {
                is ContainerStoreEntry.Admitted -> entry.workspace
                is ContainerStoreEntry.Rejected -> throw ManagementRejected("uninstall-workspaces", entry.failure.name)
            }
        removals += captureRetiredWorkspaceRemoval(store, workspace, observe)
    }
    return removals
}

internal fun captureRetiredWorkspaceRemoval(
    store: ContainerStore,
    workspace: AdmittedWorkspace,
    observe: (UninstallCleanupObservation) -> Unit,
): ManagedWorkspaceRemoval {
    val generation = requireWorkspaceRemoval(store.admitGeneration(workspace))
    requireWorkspaceRemoval(admitContainerRetirement(generation))
    for (home in workspaceProfileHomes(workspace.directory)) cleanupUninstalledUserState(home, observe)
    return ManagedWorkspaceRemoval(
        workspace.id.text,
        uninstallFilesystemIdentity(workspace.directory),
        sha256(workspace.directory.resolve("container.json")),
    )
}

private fun workspaceProfileHomes(directory: Path): List<Path> {
    val homes = mutableListOf(directory.resolve("state/home"))
    val generations = directory.resolve("generations")
    if (Files.notExists(generations, NOFOLLOW_LINKS)) return homes
    requireWorkspaceRemoval(admitPrivateContainerDirectory(generations))
    Files.newDirectoryStream(generations).use { entries ->
        for (entry in entries) {
            if (
                homes.size >= MAXIMUM_PROFILE_GENERATIONS ||
                    !Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                        .matches(entry.fileName.toString())
            )
                throw ManagementRejected("uninstall-workspaces", ContainerFailure.INVALID_RECORD.name)
            requireWorkspaceRemoval(admitPrivateContainerDirectory(entry))
            homes.add(entry.resolve("state/home"))
        }
    }
    return homes
}

/** Keeps the ownership record until last, so interrupted erasure can still check its exact bytes. */
internal fun removeWorkspaceProfiles(store: ContainerStore, proofs: List<ManagedWorkspaceRemoval>) {
    if (proofs.size > MAXIMUM_CONTAINER_ENTRIES || proofs.map { it.id }.distinct().size != proofs.size)
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.INVALID_RECORD.name)
    if (Files.notExists(store.containers, NOFOLLOW_LINKS)) return
    requireWorkspaceRemoval(admitPrivateContainerDirectory(store.containers))
    val expected = proofs.map { it.id }.toSet()
    Files.newDirectoryStream(store.containers).use { entries ->
        if (entries.any { it.fileName.toString() !in expected })
            throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
    }
    for (proof in proofs) removeWorkspaceProfile(store.containers.resolve(proof.id), proof)
    Files.delete(store.containers)
}

internal fun removeWorkspaceProfile(directory: Path, proof: ManagedWorkspaceRemoval) {
    requireWorkspaceRemoval(ContainerId.parse(proof.id))
    if (!productDigest(proof.recordSha256))
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.INVALID_RECORD.name)
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return
    requireWorkspaceRemoval(admitPrivateContainerDirectory(directory))
    requireWorkspaceIdentity(directory, proof)
    val record = directory.resolve("container.json")
    val paths = remainingWorkspaceFiles(directory, proof)
    for (path in paths.filter { it != record && it != directory }.sortedDescending()) {
        requireWorkspaceIdentity(directory, proof)
        if (path.parent.toRealPath() != path.parent || uninstallFilesystemIdentity(path).owner != proof.identity.owner)
            throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
        Files.delete(path)
    }
    requireWorkspaceIdentity(directory, proof)
    Files.deleteIfExists(record)
    Files.delete(directory)
}

private fun remainingWorkspaceFiles(directory: Path, proof: ManagedWorkspaceRemoval): List<Path> {
    val record = directory.resolve("container.json")
    val paths = Files.walk(directory).use { it.limit(MAXIMUM_PROFILE_FILES + 1).toList() }
    return requireWorkspaceRemoval(
        when {
            paths.size > MAXIMUM_PROFILE_FILES -> Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
            Files.notExists(record, NOFOLLOW_LINKS) && paths.size != 1 ->
                Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            Files.exists(record, NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(record, NOFOLLOW_LINKS) || sha256(record) != proof.recordSha256) ->
                Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            paths.any { it.fileName.toString() == ".kast" } -> Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
            else -> Refinement.Refined(paths)
        }
    )
}

private fun requireWorkspaceIdentity(directory: Path, proof: ManagedWorkspaceRemoval) {
    if (directory.toRealPath() != directory || uninstallFilesystemIdentity(directory) != proof.identity)
        throw ManagementRejected("uninstall-workspaces", ContainerFailure.FOREIGN_STATE.name)
}

private fun <T> requireWorkspaceRemoval(result: Refinement<T, ContainerFailure>): T =
    when (result) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> throw ManagementRejected("uninstall-workspaces", result.failure.name)
    }

private const val MAXIMUM_PROFILE_GENERATIONS = 1024
private const val MAXIMUM_PROFILE_FILES = 250_000L
