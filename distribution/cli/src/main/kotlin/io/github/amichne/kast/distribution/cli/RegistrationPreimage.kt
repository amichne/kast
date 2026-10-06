package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val MAXIMUM_REGISTRATION_PREIMAGE_BYTES = 16_777_216L

/** Reject symlinks at every existing ancestor, before creating directories or copying a preimage. */
@Suppress("ThrowsCount") // Each failed path proof rejects before mutation.
internal fun requireRegistrationPath(path: Path, anchor: Path) {
    val absolute = path.toAbsolutePath().normalize()
    val boundary = anchor.toAbsolutePath().normalize()
    if (!absolute.startsWith(boundary)) throw ManagementRejected("connect", "registration escaped its selected slot")
    var ancestor: Path? = absolute
    while (ancestor != null && ancestor != boundary.parent) {
        if (Files.isSymbolicLink(ancestor)) throw ManagementRejected("connect", "registration path is untrusted")
        ancestor = ancestor.parent
    }
    if (
        Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(absolute) > MAXIMUM_REGISTRATION_PREIMAGE_BYTES)
    )
        throw ManagementRejected("connect", "registration preimage cannot be captured safely")
}

internal sealed interface RegistrationRecovery {
    data object Restored : RegistrationRecovery

    data class Required(val description: String) : RegistrationRecovery
}

internal sealed class RegistrationPreimage(val destination: Path, val anchor: Path) {
    class Absent(destination: Path, anchor: Path) : RegistrationPreimage(destination, anchor)

    class Present(
        destination: Path,
        anchor: Path,
        val backup: Path,
        val digest: String,
        val permissions: Set<java.nio.file.attribute.PosixFilePermission>,
    ) : RegistrationPreimage(destination, anchor)
}

internal sealed interface RegistrationPostimage {
    data object Absent : RegistrationPostimage

    data class Present(val digest: String) : RegistrationPostimage

    data object Unavailable : RegistrationPostimage
}

internal fun registrationPostimage(path: Path): RegistrationPostimage =
    try {
        when {
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> RegistrationPostimage.Absent
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> RegistrationPostimage.Present(sha256(path))
            else -> RegistrationPostimage.Unavailable
        }
    } catch (_: java.io.IOException) {
        RegistrationPostimage.Unavailable
    }

internal fun RegistrationPreimage.restore(expected: RegistrationPostimage): RegistrationRecovery =
    try {
        requireRegistrationPath(destination, anchor)
        val observed = registrationPostimage(destination)
        val original =
            when (this) {
                is RegistrationPreimage.Absent -> RegistrationPostimage.Absent
                is RegistrationPreimage.Present -> RegistrationPostimage.Present(digest)
            }
        if (
            observed != original ||
                this is RegistrationPreimage.Present && Files.getPosixFilePermissions(destination) != permissions
        ) {
            check(expected != RegistrationPostimage.Unavailable && observed == expected)
            when (this) {
                is RegistrationPreimage.Absent -> Files.deleteIfExists(destination)
                is RegistrationPreimage.Present -> restoreBytes()
            }
        }
        discard()
        RegistrationRecovery.Restored
    } catch (_: Exception) {
        RegistrationRecovery.Required(
            when (this) {
                is RegistrationPreimage.Absent -> "remove newly created registration at $destination"
                is RegistrationPreimage.Present -> "restore $destination from preserved backup $backup"
            }
        )
    }

internal fun RegistrationPreimage.discard() {
    if (this is RegistrationPreimage.Present) Files.deleteIfExists(backup)
}

@Suppress("TooGenericExceptionCaught") // Cleanup must retain the original failure for every capture effect.
internal fun captureRegistrationPreimage(destination: Path, anchor: Path): RegistrationPreimage {
    requireRegistrationPath(destination, anchor)
    Files.createDirectories(destination.parent)
    if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return RegistrationPreimage.Absent(destination, anchor)
    val backup = Files.createTempFile(destination.parent, ".kast-", ".prior")
    try {
        Files.copy(destination, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        // Backups may contain Codex credentials. Keep access restricted even for a permissive original.
        Files.setPosixFilePermissions(
            backup,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        )
        val digest = sha256(destination)
        check(sha256(backup) == digest)
        return RegistrationPreimage.Present(
            destination,
            anchor,
            backup,
            digest,
            Files.getPosixFilePermissions(destination),
        )
    } catch (failure: Exception) {
        Files.deleteIfExists(backup)
        throw failure
    }
}

private fun RegistrationPreimage.Present.restoreBytes() {
    val staged = Files.createTempFile(destination.parent, ".kast-", ".restore")
    try {
        Files.copy(
            backup,
            staged,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        Files.setPosixFilePermissions(staged, permissions)
        Files.move(
            staged,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        check(sha256(destination) == digest)
    } finally {
        Files.deleteIfExists(staged)
    }
}
