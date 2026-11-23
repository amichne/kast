package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.InstallationLayout
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

enum class PriorInstallationPreparation {
    PREPARED,
    FILESYSTEM_REJECTED,
}

fun resetInstallationTransport(installation: Path, home: Path): PriorInstallationPreparation {
    return try {
        val runtime = InstallationLayout.of(installation).state.run
        val run = runtime.directory
        val alias =
            io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases.transportPath(
                    run.resolve("kast-${"0".repeat(43)}.sock")
                )
                .parent
        val upstream =
            io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories.transportPath(
                    runtime.upstreamSocket
                )
                .parent
        val owner = Files.getOwner(home, LinkOption.NOFOLLOW_LINKS)
        for (path in setOf(alias, upstream).filterNot { it == run }) {
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
