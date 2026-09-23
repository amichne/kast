package io.github.amichne.kast.appserver

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/** Exact ownership proof for the legacy one-shot login bootstrap being retired. */
internal sealed interface LegacyLoginBootstrapObservation {
    data object Absent : LegacyLoginBootstrapObservation

    data object Exact : LegacyLoginBootstrapObservation

    data object Rejected : LegacyLoginBootstrapObservation
}

internal object LegacyLoginBootstrap {
    fun observe(agent: Path, userHome: Path, expected: String): LegacyLoginBootstrapObservation =
        try {
            val owner = Files.getOwner(userHome)
            if (!admittedDirectory(agent.parent.parent, owner) || !admittedDirectory(agent.parent, owner))
                return LegacyLoginBootstrapObservation.Rejected
            if (Files.notExists(agent, LinkOption.NOFOLLOW_LINKS)) return LegacyLoginBootstrapObservation.Absent
            if (!admittedFile(agent, owner)) return LegacyLoginBootstrapObservation.Rejected
            val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
            if (expectedBytes.size > MAXIMUM_BYTES) return LegacyLoginBootstrapObservation.Rejected
            val observed =
                Files.newInputStream(agent, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_BYTES + 1) }
            if (observed.contentEquals(expectedBytes)) LegacyLoginBootstrapObservation.Exact
            else LegacyLoginBootstrapObservation.Rejected
        } catch (_: IOException) {
            LegacyLoginBootstrapObservation.Rejected
        } catch (_: SecurityException) {
            LegacyLoginBootstrapObservation.Rejected
        } catch (_: UnsupportedOperationException) {
            LegacyLoginBootstrapObservation.Rejected
        }

    private fun admittedDirectory(directory: Path, owner: UserPrincipal): Boolean =
        when {
            Files.notExists(directory, LinkOption.NOFOLLOW_LINKS) -> true
            !Files.exists(directory, LinkOption.NOFOLLOW_LINKS) -> false
            !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) -> false
            directory.toRealPath() != directory -> false
            Files.getOwner(directory) != owner -> false
            else -> true
        }

    private fun admittedFile(agent: Path, owner: UserPrincipal): Boolean =
        Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS) &&
            Files.getOwner(agent, LinkOption.NOFOLLOW_LINKS) == owner &&
            Files.getPosixFilePermissions(agent, LinkOption.NOFOLLOW_LINKS) ==
                PosixFilePermissions.fromString("rw-------")

    private const val MAXIMUM_BYTES = 65_536
}
