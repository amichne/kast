package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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
