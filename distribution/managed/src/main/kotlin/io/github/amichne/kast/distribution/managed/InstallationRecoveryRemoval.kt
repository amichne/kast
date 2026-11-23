package io.github.amichne.kast.distribution.managed

import com.sun.security.auth.module.UnixSystem
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// Uninstall removes the JVM's native libraries. Capture the process UID while those libraries still exist.
val removalProcessUid: Long = UnixSystem().uid

/** Must run before payload removal. Any recovery content is foreign; only an empty private directory is owned. */
fun admitRecoveryRemoval(root: Path): Refinement<ManagedRecoveryRemovalProof, RecoveryRemovalFailure> =
    recoveryRemovalBoundary {
        if (!validRemovalRoot(root)) return@recoveryRemovalBoundary rejected(RecoveryRemovalFailure.ROOT_REJECTED)
        admitEmptyRecovery(root, InstallationLayout.of(root).management.recovery, removalProcessUid)
    }

private fun admitEmptyRecovery(
    root: Path,
    directory: Path,
    uid: Long,
): Refinement<ManagedRecoveryRemovalProof, RecoveryRemovalFailure> {
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(ManagedRecoveryRemovalProof.Absent)
    if (!privateRecoveryDirectory(directory, uid)) return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val identity = recoveryIdentity(directory)
    if (!emptyDirectory(directory)) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (!privateDirectoryIdentity(directory, uid, identity)) return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    return Refinement.Refined(ManagedRecoveryRemovalProof.Empty(root.toString(), identity))
}

/** Delete only captured state after retirement; retry after removal is idempotent. */
fun removeRetiredRecovery(root: Path, proof: ManagedRecoveryRemovalProof): RecoveryRemovalOutcome {
    val removed = recoveryRemovalBoundary { removeAdmittedRecovery(root, proof) }
    return when (removed) {
        is Refinement.Refined -> RecoveryRemovalOutcome.Removed
        is Refinement.Rejected -> RecoveryRemovalOutcome.Rejected(removed.failure)
    }
}

private fun removeAdmittedRecovery(
    root: Path,
    proof: ManagedRecoveryRemovalProof,
): Refinement<Unit, RecoveryRemovalFailure> {
    if (!validRemovalRoot(root)) return rejected(RecoveryRemovalFailure.ROOT_REJECTED)
    if (!Files.notExists(root, NOFOLLOW_LINKS)) return rejected(RecoveryRemovalFailure.INSTALLATION_NOT_RETIRED)
    val directory = InstallationLayout.of(root).management.recovery
    return when (proof) {
        ManagedRecoveryRemovalProof.Absent ->
            if (Files.notExists(directory, NOFOLLOW_LINKS)) Refinement.Refined(Unit)
            else rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
        is ManagedRecoveryRemovalProof.Empty -> removeEmptyRecovery(root, directory, proof)
    }
}

private fun removeEmptyRecovery(
    root: Path,
    directory: Path,
    proof: ManagedRecoveryRemovalProof.Empty,
): Refinement<Unit, RecoveryRemovalFailure> {
    val uid = removalProcessUid
    if (proof.installation != root.toString() || proof.recoveryDirectoryIdentity.owner != uid)
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    if (!privateDirectoryIdentity(directory, uid, proof.recoveryDirectoryIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (!emptyDirectory(directory)) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (!Files.notExists(root, NOFOLLOW_LINKS)) return rejected(RecoveryRemovalFailure.INSTALLATION_NOT_RETIRED)
    if (!privateDirectoryIdentity(directory, uid, proof.recoveryDirectoryIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    Files.delete(directory)
    return Refinement.Refined(Unit)
}

private fun privateDirectoryIdentity(path: Path, uid: Long, expected: InstallationFilesystemIdentity): Boolean =
    privateRecoveryDirectory(path, uid) && recoveryIdentity(path) == expected

private fun validRemovalRoot(root: Path): Boolean =
    root.isAbsolute &&
        root.normalize() == root &&
        root.fileName?.toString() == "installation" &&
        root.parent != null &&
        Files.isDirectory(root.parent, NOFOLLOW_LINKS) &&
        root.parent.toRealPath() == root.parent

private fun privateRecoveryDirectory(path: Path, uid: Long): Boolean =
    Files.isDirectory(path, NOFOLLOW_LINKS) &&
        path.toRealPath() == path &&
        recoveryIdentity(path).owner == uid &&
        Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")

private fun emptyDirectory(directory: Path): Boolean =
    Files.newDirectoryStream(directory).use { entries -> !entries.iterator().hasNext() }

private fun recoveryIdentity(path: Path) =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong(),
    )

private fun rejected(failure: RecoveryRemovalFailure) = Refinement.Rejected(failure)

private inline fun <T> recoveryRemovalBoundary(
    action: () -> Refinement<T, RecoveryRemovalFailure>
): Refinement<T, RecoveryRemovalFailure> =
    try {
        action()
    } catch (_: java.io.IOException) {
        rejected(RecoveryRemovalFailure.IO_UNAVAILABLE)
    } catch (_: SecurityException) {
        rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    } catch (_: java.nio.file.InvalidPathException) {
        rejected(RecoveryRemovalFailure.ROOT_REJECTED)
    } catch (_: UnsupportedOperationException) {
        rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    }
