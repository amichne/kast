package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal fun captureUninstallManagedRoot(root: Path): InstallationFilesystemIdentity {
    if (
        !Files.isDirectory(root, NOFOLLOW_LINKS) ||
            root.toRealPath() != root ||
            uninstallFilesystemIdentity(root).owner != com.sun.security.auth.module.UnixSystem().uid
    )
        throw ManagementRejected("uninstall-root", "managed root ownership is unproven")
    return uninstallFilesystemIdentity(root)
}

internal fun requireUninstallRootInventory(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    captureUninstallManagedRoot(root)
    val known = setOf("installation", "recovery", "activation.lock", "management.lock", "management.json")
    Files.newDirectoryStream(root).use { entries ->
        if (entries.any { it.fileName.toString() !in known })
            throw ManagementRejected(
                "uninstall-root",
                "unrecognized managed-root artifacts remain; retained cleanup receipt",
            )
    }
}

internal fun cleanupUninstallActivationLock(root: Path, observe: (UninstallCleanupObservation) -> Unit) {
    uninstallManagedRootEffect(UninstallArtifact.ACTIVATION_LOCK, observe) {
        removeUninstallActivationLock(root)
    }
}

private fun removeUninstallActivationLock(root: Path) {
    val path = root.resolve("activation.lock")
    if (Files.notExists(path, NOFOLLOW_LINKS)) return
    if (
        !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            Files.getOwner(path) != Files.getOwner(root) ||
            Files.size(path) != 0L
    )
        throw ManagementRejected("uninstall-activation-lock", "activation lock ownership is unproven")
    val identity = uninstallFilesystemIdentity(path)
    FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
        deleteHeldUninstallActivationLock(path, identity, channel)
    }
}

private fun deleteHeldUninstallActivationLock(
    path: Path,
    identity: InstallationFilesystemIdentity,
    channel: FileChannel,
) {
    val held =
        try {
            channel.tryLock()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            null
        }
            ?: throw ManagementRejected(
                "uninstall-activation-lock",
                "activation is still running; retained cleanup receipt",
            )
    held.use {
        if (uninstallFilesystemIdentity(path) != identity)
            throw ManagementRejected("uninstall-activation-lock", "activation lock identity changed")
        Files.delete(path)
    }
}

internal fun cleanupEmptyUninstallRoot(
    root: Path,
    expected: InstallationFilesystemIdentity,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    uninstallManagedRootEffect(UninstallArtifact.MANAGED_ROOT, observe) {
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            if (captureUninstallManagedRoot(root) != expected)
                throw ManagementRejected(
                    "uninstall-root",
                    "managed root identity changed; retained exterior cleanup journal",
                )
            Files.newDirectoryStream(root).use { entries ->
                if (entries.iterator().hasNext())
                    throw ManagementRejected(
                        "uninstall-root",
                        "managed root is not empty; retained exterior cleanup journal",
                    )
            }
            Files.delete(root)
        }
    }
}

internal fun uninstallFilesystemIdentity(path: Path) =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong(),
    )

private inline fun uninstallManagedRootEffect(
    artifact: UninstallArtifact,
    observe: (UninstallCleanupObservation) -> Unit,
    effect: () -> Unit,
) {
    observe(UninstallCleanupObservation.Started(artifact))
    try {
        effect()
    } catch (rejected: ManagementRejected) {
        observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.OWNERSHIP_UNPROVEN))
        throw rejected
    } catch (_: java.io.IOException) {
        observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.FILESYSTEM_REJECTED))
        throw ManagementRejected(
            "uninstall-${artifact.name.lowercase()}",
            "filesystem refused cleanup; retained exterior cleanup journal",
        )
    }
    observe(UninstallCleanupObservation.Completed(artifact))
}
