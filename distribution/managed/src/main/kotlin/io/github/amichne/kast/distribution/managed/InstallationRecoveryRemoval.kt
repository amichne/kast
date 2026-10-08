package io.github.amichne.kast.distribution.managed

import com.sun.security.auth.module.UnixSystem
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Must run before payload removal. Existing receipt admission owns stage and installation proof. */
fun admitRecoveryRemoval(root: Path): Refinement<ManagedRecoveryRemovalProof, RecoveryRemovalFailure> =
    recoveryRemovalBoundary {
        if (!validRemovalRoot(root)) return@recoveryRemovalBoundary rejected(RecoveryRemovalFailure.ROOT_REJECTED)
        val bundle = root.parent.resolve("recovery/installation")
        val uid = UnixSystem().uid
        if (Files.notExists(bundle, NOFOLLOW_LINKS)) admitEmptyRecovery(root, bundle.parent, uid)
        else admitCapturedRecovery(root, bundle, uid)
    }

private fun admitEmptyRecovery(
    root: Path,
    directory: Path,
    uid: Long,
): Refinement<ManagedRecoveryRemovalProof, RecoveryRemovalFailure> {
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(ManagedRecoveryRemovalProof.Absent)
    if (!privateRecoveryDirectory(directory, uid)) return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val identity = recoveryIdentity(directory)
    if (names(directory).isNotEmpty()) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (!privateDirectoryIdentity(directory, uid, identity)) return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    return Refinement.Refined(ManagedRecoveryRemovalProof.Empty(root.toString(), identity))
}

private fun admitCapturedRecovery(
    root: Path,
    bundle: Path,
    uid: Long,
): Refinement<ManagedRecoveryRemovalProof, RecoveryRemovalFailure> {
    if (!privateRecoveryDirectory(bundle.parent, uid) || !privateRecoveryDirectory(bundle, uid))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (names(bundle) != RECOVERY_FILES) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (names(bundle.parent) != setOf("installation")) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    val installation =
        when (val admitted = admitRecoveryRemovalIdentity(root)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    if (installation.owner != uid) return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val directoryIdentity = recoveryIdentity(bundle.parent)
    val bundleIdentity = recoveryIdentity(bundle)
    val captured =
        when (val files = captureRecoveryBundleFiles(bundle, uid)) {
            is Refinement.Refined -> files.value
            is Refinement.Rejected -> return files
        }
    if (!unchangedAdmission(root, bundle, installation, directoryIdentity, bundleIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    return Refinement.Refined(
        ManagedRecoveryRemovalProof.Admitted(
            root.toString(),
            installation,
            directoryIdentity,
            bundleIdentity,
            captured.receipt,
            captured.recovery,
            captured.lifecycle,
        )
    )
}

private class CapturedRecoveryBundleFiles(
    val receipt: CapturedRecoveryFile,
    val recovery: CapturedRecoveryFile,
    val lifecycle: CapturedRecoveryFile,
)

private fun captureRecoveryBundleFiles(
    bundle: Path,
    uid: Long,
): Refinement<CapturedRecoveryBundleFiles, RecoveryRemovalFailure> {
    val receipt =
        when (val captured = captureRecoveryFile(bundle.resolve(RECEIPT), uid)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return captured
        }
    val recovery =
        when (val captured = captureRecoveryFile(bundle.resolve(RECOVERY_SCRIPT), uid)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return captured
        }
    val lifecycle =
        when (val captured = captureRecoveryFile(bundle.resolve(LIFECYCLE_SCRIPT), uid)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return captured
        }
    return Refinement.Refined(CapturedRecoveryBundleFiles(receipt, recovery, lifecycle))
}

private fun unchangedAdmission(
    root: Path,
    bundle: Path,
    installation: InstallationFilesystemIdentity,
    directory: InstallationFilesystemIdentity,
    capturedBundle: InstallationFilesystemIdentity,
): Boolean {
    if (directory != recoveryIdentity(bundle.parent) || capturedBundle != recoveryIdentity(bundle)) return false
    return installation == recoveryIdentity(root) && names(bundle) == RECOVERY_FILES
}

/** Delete only captured state after retirement; partial deletion resumes from the same inventory. */
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
    val directory = root.parent.resolve("recovery")
    return when (proof) {
        ManagedRecoveryRemovalProof.Absent ->
            if (Files.notExists(directory, NOFOLLOW_LINKS)) Refinement.Refined(Unit)
            else rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
        is ManagedRecoveryRemovalProof.Empty -> removeEmptyRecovery(root, directory, proof)
        is ManagedRecoveryRemovalProof.Admitted -> removeCapturedRecovery(root, directory, proof)
    }
}

private fun removeEmptyRecovery(
    root: Path,
    directory: Path,
    proof: ManagedRecoveryRemovalProof.Empty,
): Refinement<Unit, RecoveryRemovalFailure> {
    val uid = UnixSystem().uid
    if (proof.installation != root.toString() || proof.recoveryDirectoryIdentity.owner != uid)
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    if (!privateDirectoryIdentity(directory, uid, proof.recoveryDirectoryIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (names(directory).isNotEmpty()) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (!Files.notExists(root, NOFOLLOW_LINKS)) return rejected(RecoveryRemovalFailure.INSTALLATION_NOT_RETIRED)
    if (!privateDirectoryIdentity(directory, uid, proof.recoveryDirectoryIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    Files.delete(directory)
    return Refinement.Refined(Unit)
}

private fun removeCapturedRecovery(
    root: Path,
    directory: Path,
    proof: ManagedRecoveryRemovalProof.Admitted,
): Refinement<Unit, RecoveryRemovalFailure> {
    val uid = UnixSystem().uid
    if (proof.installation != root.toString() || proof.installationIdentity.owner != uid)
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    if (!privateDirectoryIdentity(directory, uid, proof.recoveryDirectoryIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val bundle = directory.resolve("installation")
    if (!closedRecoveryParent(directory, bundle)) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (!Files.notExists(bundle, NOFOLLOW_LINKS)) {
        when (val removed = removeCapturedBundle(root, bundle, proof, uid)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return removed
        }
    }
    if (names(directory).isNotEmpty()) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (!privateDirectoryIdentity(directory, uid, proof.recoveryDirectoryIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    Files.delete(directory)
    return Refinement.Refined(Unit)
}

private fun removeCapturedBundle(
    root: Path,
    bundle: Path,
    proof: ManagedRecoveryRemovalProof.Admitted,
    uid: Long,
): Refinement<Unit, RecoveryRemovalFailure> {
    if (!privateDirectoryIdentity(bundle, uid, proof.bundleIdentity))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (names(bundle).any { it !in RECOVERY_FILES }) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    val inventory =
        listOf(
            RECOVERY_SCRIPT to proof.recoveryScript,
            LIFECYCLE_SCRIPT to proof.lifecycleScript,
            RECEIPT to proof.receipt,
        )
    when (val admission = revalidateCapturedFiles(bundle, inventory, uid)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    for ((name, captured) in inventory) {
        when (val removed = removeCapturedFile(root, bundle, name, captured, proof)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return removed
        }
    }
    if (!privateDirectoryIdentity(bundle, uid, proof.bundleIdentity) || names(bundle).isNotEmpty())
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    Files.delete(bundle)
    return Refinement.Refined(Unit)
}

private fun revalidateCapturedFiles(
    bundle: Path,
    inventory: List<Pair<String, CapturedRecoveryFile>>,
    uid: Long,
): Refinement<Unit, RecoveryRemovalFailure> {
    for ((name, captured) in inventory) {
        when (val current = revalidateCapturedFile(bundle.resolve(name), captured, uid)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return current
        }
    }
    return Refinement.Refined(Unit)
}

private fun revalidateCapturedFile(
    path: Path,
    captured: CapturedRecoveryFile,
    uid: Long,
): Refinement<Unit, RecoveryRemovalFailure> {
    if (captured.identity.owner != uid || RecoveryBundleDigest.parse(captured.digest.value) is Refinement.Rejected)
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (Files.notExists(path, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    return when (val current = captureRecoveryFile(path, uid)) {
        is Refinement.Refined ->
            if (current.value == captured) Refinement.Refined(Unit)
            else rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
        is Refinement.Rejected -> current
    }
}

private fun removeCapturedFile(
    root: Path,
    bundle: Path,
    name: String,
    captured: CapturedRecoveryFile,
    proof: ManagedRecoveryRemovalProof.Admitted,
): Refinement<Unit, RecoveryRemovalFailure> {
    val uid = UnixSystem().uid
    if (names(bundle.parent) != setOf("installation")) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (names(bundle).any { it !in RECOVERY_FILES }) return rejected(RecoveryRemovalFailure.FOREIGN_CONTENT)
    if (
        !privateDirectoryIdentity(bundle, uid, proof.bundleIdentity) ||
            !privateDirectoryIdentity(bundle.parent, uid, proof.recoveryDirectoryIdentity)
    )
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    if (!Files.notExists(root, NOFOLLOW_LINKS)) return rejected(RecoveryRemovalFailure.INSTALLATION_NOT_RETIRED)
    val file = bundle.resolve(name)
    when (val current = revalidateCapturedFile(file, captured, uid)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return current
    }
    if (!Files.notExists(file, NOFOLLOW_LINKS)) Files.delete(file)
    return Refinement.Refined(Unit)
}

private fun privateDirectoryIdentity(path: Path, uid: Long, expected: InstallationFilesystemIdentity): Boolean =
    privateRecoveryDirectory(path, uid) && recoveryIdentity(path) == expected

private fun closedRecoveryParent(directory: Path, bundle: Path): Boolean {
    val expected = if (Files.notExists(bundle, NOFOLLOW_LINKS)) emptySet() else setOf("installation")
    return names(directory) == expected
}

private fun captureRecoveryFile(path: Path, uid: Long): Refinement<CapturedRecoveryFile, RecoveryRemovalFailure> {
    if (
        !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            path.toRealPath() != path ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != PosixFilePermissions.fromString("rw-------")
    )
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val before = recoveryIdentity(path)
    if (before.owner != uid) return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_RECOVERY_FILE_BYTES + 1) }
    if (bytes.size > MAXIMUM_RECOVERY_FILE_BYTES || before != recoveryIdentity(path))
        return rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    val digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    return when (val parsed = RecoveryBundleDigest.parse(digest)) {
        is Refinement.Refined -> Refinement.Refined(CapturedRecoveryFile(before, parsed.value))
        is Refinement.Rejected -> parsed
    }
}

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

private fun names(directory: Path): Set<String> =
    Files.newDirectoryStream(directory).use { entries ->
        entries.asSequence().take(RECOVERY_FILES.size + 1).map { it.fileName.toString() }.toSet()
    }

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
    } catch (_: kotlinx.serialization.SerializationException) {
        rejected(RecoveryRemovalFailure.RECEIPT_REJECTED)
    } catch (_: SecurityException) {
        rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    } catch (_: java.nio.file.InvalidPathException) {
        rejected(RecoveryRemovalFailure.ROOT_REJECTED)
    } catch (_: UnsupportedOperationException) {
        rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    }

private const val RECEIPT = "receipt.json"
private const val RECOVERY_SCRIPT = "installation-recovery.py"
private const val LIFECYCLE_SCRIPT = "installation-lifecycle.py"
private val RECOVERY_FILES = setOf(RECEIPT, RECOVERY_SCRIPT, LIFECYCLE_SCRIPT)
private const val MAXIMUM_RECOVERY_FILE_BYTES = 1_048_576
