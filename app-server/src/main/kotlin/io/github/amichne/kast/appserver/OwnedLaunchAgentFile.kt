package io.github.amichne.kast.appserver

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/** Exact bytes and private ownership of a launch agent file that this installation published. */
internal sealed interface OwnedLaunchAgentObservation {
    data object Absent : OwnedLaunchAgentObservation

    data object Exact : OwnedLaunchAgentObservation

    data object Rejected : OwnedLaunchAgentObservation
}

internal object OwnedLaunchAgentFile {
    fun observe(agent: Path, userHome: Path, expected: String): OwnedLaunchAgentObservation =
        try {
            val owner = Files.getOwner(userHome)
            if (!admittedDirectory(agent.parent.parent, owner) || !admittedDirectory(agent.parent, owner))
                return OwnedLaunchAgentObservation.Rejected
            if (Files.notExists(agent, LinkOption.NOFOLLOW_LINKS)) return OwnedLaunchAgentObservation.Absent
            if (!admittedFile(agent, owner)) return OwnedLaunchAgentObservation.Rejected
            val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
            if (expectedBytes.size > MAXIMUM_BYTES) return OwnedLaunchAgentObservation.Rejected
            val observed =
                Files.newInputStream(agent, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_BYTES + 1) }
            if (observed.contentEquals(expectedBytes)) OwnedLaunchAgentObservation.Exact
            else OwnedLaunchAgentObservation.Rejected
        } catch (_: IOException) {
            OwnedLaunchAgentObservation.Rejected
        } catch (_: SecurityException) {
            OwnedLaunchAgentObservation.Rejected
        } catch (_: UnsupportedOperationException) {
            OwnedLaunchAgentObservation.Rejected
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
