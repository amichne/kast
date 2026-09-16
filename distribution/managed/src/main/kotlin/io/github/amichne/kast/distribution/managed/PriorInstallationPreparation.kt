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
