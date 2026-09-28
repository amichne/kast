package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Reject symlinks at every existing ancestor, before creating directories or copying a preimage. */
internal fun requireRegistrationPath(path: Path, anchor: Path) {
    val absolute = path.toAbsolutePath().normalize()
    val boundary = anchor.toAbsolutePath().normalize()
    if (!absolute.startsWith(boundary)) throw ManagementRejected("connect", "registration escaped its selected slot")
    var ancestor: Path? = absolute
    while (ancestor != null && ancestor != boundary.parent) {
        if (Files.isSymbolicLink(ancestor)) throw ManagementRejected("connect", "registration path is untrusted")
        ancestor = ancestor.parent
    }
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) &&
        (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || Files.size(absolute) > 16_777_216))
        throw ManagementRejected("connect", "registration preimage cannot be captured safely")
}

internal sealed interface RegistrationRecovery {
    data object Restored : RegistrationRecovery
    data class Required(val description: String) : RegistrationRecovery
}

internal sealed class RegistrationPreimage(val destination: Path, val anchor: Path) {
    class Absent(destination: Path, anchor: Path) : RegistrationPreimage(destination, anchor)
    class Present(destination: Path, anchor: Path, val backup: Path, val digest: String, val permissions: Set<java.nio.file.attribute.PosixFilePermission>) : RegistrationPreimage(destination, anchor)

    fun restore(): RegistrationRecovery = try {
        requireRegistrationPath(destination, anchor)
        when (this) {
            is Absent -> Files.deleteIfExists(destination)
            is Present -> {
                val staged = Files.createTempFile(destination.parent, ".kast-", ".restore")
                try {
                    Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    Files.setPosixFilePermissions(staged, permissions)
                    Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    check(sha256(destination) == digest)
                } finally { Files.deleteIfExists(staged) }
            }
        }
        discard()
        RegistrationRecovery.Restored
    } catch (_: Exception) {
        RegistrationRecovery.Required(when (this) {
            is Absent -> "remove newly created registration at $destination"
            is Present -> "restore $destination from preserved backup $backup"
        })
    }

    fun discard() { if (this is Present) Files.deleteIfExists(backup) }

    companion object {
        fun capture(destination: Path, anchor: Path): RegistrationPreimage {
            requireRegistrationPath(destination, anchor)
            Files.createDirectories(destination.parent)
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return Absent(destination, anchor)
            val backup = Files.createTempFile(destination.parent, ".kast-", ".prior")
            try {
                Files.copy(destination, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                // Backups may contain Codex credentials. Keep access restricted even for a permissive original.
                Files.setPosixFilePermissions(backup, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                val digest = sha256(destination)
                check(sha256(backup) == digest)
                return Present(destination, anchor, backup, digest, Files.getPosixFilePermissions(destination))
            } catch (failure: Exception) { Files.deleteIfExists(backup); throw failure }
        }
    }
}
