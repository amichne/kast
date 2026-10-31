package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

/** Installer-only path admission. It never picks a fallback after a rejected destination. */
@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
internal fun preflightPublicExecutable(root: Path, environment: Map<String, String>): InstallDestination {
    if (Files.exists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS))
        throw ManagementRejected("path-preflight", "uninstall cleanup is incomplete; retry verified removal")
    val existing =
        when (val read = readReceipt(root)) {
            is ReceiptRead.Read -> read.receipt
            is ReceiptRead.Unavailable -> {
                if (Files.exists(receiptPath(root), LinkOption.NOFOLLOW_LINKS))
                    throw ManagementRejected("path-preflight", read.reason)
                null
            }
        }
    val path =
        existing?.let { Path.of(it.executable) }
            ?: when (
                val selection =
                    selectDestination(
                        environment["HOME"] ?: throw ManagementRejected("path-preflight", "HOME is unavailable"),
                        environment["XDG_CONFIG_HOME"],
                        environment["PATH"],
                    )
            ) {
                is DestinationSelection.Selected -> selection.executable
                is DestinationSelection.Rejected ->
                    throw ManagementRejected("path-preflight", selection.failure.name.lowercase())
            }
    val parent = path.parent ?: throw ManagementRejected("path-preflight", "destination has no parent")
    val ancestor =
        generateSequence(parent) { it.parent }.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: throw ManagementRejected("path-preflight", "destination parent is unavailable")
    if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(ancestor))
        throw ManagementRejected("path-preflight", "destination is unwritable")
    val physicalAncestor =
        try {
            ancestor.toRealPath()
        } catch (_: java.io.IOException) {
            throw ManagementRejected("path-preflight", "destination parent is unavailable")
        }
    if (physicalAncestor != ancestor) throw ManagementRejected("path-preflight", "destination parent must be physical")
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        val owned =
            existing != null &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                sha256(path) == existing.executableSha256
        if (!owned) throw ManagementRejected("path-preflight", "destination belongs to another owner")
    } else if (existing != null) {
        throw ManagementRejected("path-preflight", "recorded executable is missing")
    }
    val onPath =
        environment["PATH"]?.split(':')?.any { entry ->
            entry.isNotEmpty() &&
                try {
                    Path.of(entry).toAbsolutePath().normalize() == parent
                } catch (_: Exception) {
                    false
                }
        } ?: false
    return InstallDestination(path, onPath, existing)
}

internal fun commitPublicExecutable(
    root: Path,
    environment: Map<String, String>,
    channel: ReleaseChannel,
    composition: ManagementPublication = ManagementPublication.Preserve,
): InstallDestination {
    val destination = preflightPublicExecutable(root, environment)
    val source = root.resolve("installation/share/kast/libexec/kast-management")
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(source))
        throw ManagementRejected("path-commit", "verified native executable is unavailable")
    try {
        publishPublicExecutable(root, source, destination, channel, composition)
    } catch (_: Exception) {
        throw ManagementRejected("path-commit", "executable or receipt could not be committed")
    }
    return destination
}

private fun publishPublicExecutable(
    root: Path,
    source: Path,
    destination: InstallDestination,
    channel: ReleaseChannel,
    composition: ManagementPublication,
) {
    val path = destination.path
    Files.createDirectories(path.parent)
    val staged = Files.createTempFile(path.parent, ".kast-", ".new")
    val previous =
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.createTempFile(path.parent, ".kast-", ".old")
        else null
    try {
        Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwxr-xr-x"))
        if (previous != null) Files.copy(path, previous, StandardCopyOption.REPLACE_EXISTING)
        val next = nextManagementReceipt(root, destination, staged, channel, composition)
        Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        commitExecutableReceipt(root, next, path, previous)
    } finally {
        Files.deleteIfExists(staged)
        if (previous != null) Files.deleteIfExists(previous)
    }
}

private fun commitExecutableReceipt(root: Path, next: ManagementReceipt, path: Path, previous: Path?) {
    try {
        writeManagementReceipt(root, next)
    } catch (failure: java.io.IOException) {
        if (previous != null)
            Files.move(previous, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.deleteIfExists(path)
        throw failure
    }
}

private fun nextManagementReceipt(
    root: Path,
    destination: InstallDestination,
    staged: Path,
    channel: ReleaseChannel,
    composition: ManagementPublication,
): ManagementReceipt {
    val prior = destination.previous
    val policy =
        when (composition) {
            ManagementPublication.Preserve ->
                prior?.updatePolicy
                    ?: if (channel == ReleaseChannel.STABLE) UpdatePolicy.TrackStable else UpdatePolicy.TrackMain
            is ManagementPublication.Selected -> composition.binding.policy
        }
    val identity =
        when (composition) {
            ManagementPublication.Preserve -> prior?.installedBuild
            is ManagementPublication.Selected -> composition.binding.identity
        }
    return ManagementReceipt(
        2,
        root.toString(),
        destination.path.toString(),
        sha256(staged),
        channel,
        prior?.registrations.orEmpty(),
        policy,
        identity,
        prior?.guidance.orEmpty(),
    )
}

internal fun writeManagementReceipt(root: Path, receipt: ManagementReceipt) {
    val path = receiptPath(root)
    val temporary = Files.createTempFile(root, ".management-", ".json")
    try {
        Files.writeString(temporary, managementJson.encodeToString(receipt) + "\n")
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal enum class ManagementRootFailure(val reason: String) {
    HOME_UNAVAILABLE("HOME is unavailable"),
    EMPTY_PATH("installation root is empty"),
    INVALID_PATH("installation root is invalid"),
    RELATIVE_PATH("installation root must be absolute"),
    UNNORMALIZED_PATH("installation root must be normalized"),
    ALTERNATE_INSTALLATION("installation root must be HOME/.local/share/kast"),
}

internal sealed interface ManagementRootResolution {
    class Selected private constructor(val root: Path) : ManagementRootResolution {
        companion object {
            fun admit(homeRaw: String, explicitRoot: String?): ManagementRootResolution {
                val home =
                    when (val admitted = ManagementPathAdmission.admit(homeRaw)) {
                        is ManagementPathAdmission.Rejected -> return Rejected(admitted.failure)
                        is ManagementPathAdmission.Admitted -> admitted.path
                    }
                val canonical = home.resolve(".local/share/kast")
                if (explicitRoot == null) return Selected(canonical)
                return when (val admitted = ManagementPathAdmission.admit(explicitRoot)) {
                    is ManagementPathAdmission.Rejected -> Rejected(admitted.failure)
                    is ManagementPathAdmission.Admitted ->
                        if (admitted.path == canonical) Selected(canonical)
                        else Rejected(ManagementRootFailure.ALTERNATE_INSTALLATION)
                }
            }
        }
    }

    data class Rejected(val failure: ManagementRootFailure) : ManagementRootResolution
}

private sealed interface ManagementPathAdmission {
    data class Admitted(val path: Path) : ManagementPathAdmission

    data class Rejected(val failure: ManagementRootFailure) : ManagementPathAdmission

    companion object {
        fun admit(raw: String): ManagementPathAdmission {
            if (raw.isEmpty()) return Rejected(ManagementRootFailure.EMPTY_PATH)
            val path =
                try {
                    Path.of(raw)
                } catch (_: IllegalArgumentException) {
                    return Rejected(ManagementRootFailure.INVALID_PATH)
                }
            return when {
                !path.isAbsolute -> Rejected(ManagementRootFailure.RELATIVE_PATH)
                path.normalize() != path -> Rejected(ManagementRootFailure.UNNORMALIZED_PATH)
                else -> Admitted(path)
            }
        }
    }
}

internal fun writeConnectionConfiguration(path: Path, configuration: ConnectionConfiguration) {
    val staged = Files.createTempFile(path.parent, ".kast-config-", ".new")
    try {
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(staged, encodeConnectionConfiguration(configuration))
        Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staged)
    }
}

internal fun writePublicResetRecovery(file: PublicRecoveryFile, receipt: ManagementReceipt): LifecycleEffect =
    try {
        Files.writeString(
            file.path,
            managementJson.encodeToString(receipt),
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        LifecycleEffect.Completed
    } catch (_: java.io.IOException) {
        LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
    } catch (_: SecurityException) {
        LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
    }
