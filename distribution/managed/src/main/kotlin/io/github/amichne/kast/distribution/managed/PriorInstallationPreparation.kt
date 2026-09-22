package io.github.amichne.kast.distribution.managed

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

enum class PriorInstallationPreparation {
    PREPARED,
    FILESYSTEM_REJECTED,
}

/** Fences old launches and removes only the login agent derived from this exact installation path. */
fun preparePriorInstallationReplacement(prior: Path, home: Path): PriorInstallationPreparation =
    try {
        if (Files.isDirectory(prior, LinkOption.NOFOLLOW_LINKS) && prior.toRealPath() == prior) {
            Files.newByteChannel(
                    prior.resolve(".recovery-detached"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                .use {}
        }
        val digest =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prior.toString().toByteArray()))
        val label = "io.github.amichne.kast.broker.${digest.take(32)}"
        Files.deleteIfExists(home.resolve("Library/LaunchAgents/$label.login.plist"))
        PriorInstallationPreparation.PREPARED
    } catch (_: java.io.IOException) {
        PriorInstallationPreparation.FILESYSTEM_REJECTED
    } catch (_: SecurityException) {
        PriorInstallationPreparation.FILESYSTEM_REJECTED
    }

/** Moves one installation-owned entry aside without traversing it. */
fun quarantineInstallationEntry(path: Path) {
    Files.move(
        path,
        path.resolveSibling(".replaced-${path.fileName}-${java.util.UUID.randomUUID()}"),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
}

fun resetInstallationTransport(installation: Path, home: Path): PriorInstallationPreparation {
    return try {
        val run = installation.resolve("state/run")
        val alias =
            io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases.transportPath(
                    run.resolve("kast-${"0".repeat(43)}.sock")
                )
                .parent
        val upstream =
            io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories.transportPath(
                    run.resolve("u.sock")
                )
                .parent
        val owner = Files.getOwner(home, LinkOption.NOFOLLOW_LINKS)
        for (path in setOf(alias, upstream) - run) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) != owner) {
                return PriorInstallationPreparation.FILESYSTEM_REJECTED
            }
            val removed = removeTransportEntry(path)
            if (removed != PriorInstallationPreparation.PREPARED) return removed
        }
        PriorInstallationPreparation.PREPARED
    } catch (_: java.io.IOException) {
        PriorInstallationPreparation.FILESYSTEM_REJECTED
    } catch (_: SecurityException) {
        PriorInstallationPreparation.FILESYSTEM_REJECTED
    }
}

/** Never follows a link; physical upstream directories contain at most the one named socket. */
private fun removeTransportEntry(path: Path): PriorInstallationPreparation {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.newDirectoryStream(path).use { entries ->
            val children = entries.take(2)
            if (
                children.any { it.fileName.toString() != "u.sock" || Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            ) {
                return PriorInstallationPreparation.FILESYSTEM_REJECTED
            }
            children.forEach(Files::delete)
        }
    }
    Files.delete(path)
    return PriorInstallationPreparation.PREPARED
}
