package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class InstalledToolAdmission {
    AVAILABLE,
    STOPPED,
    UNPROVEN,
}

/** The native management command owns this fence; absent authority fails closed. */
internal fun observeInstalledToolAdmission(installation: Path): InstalledToolAdmission =
    try {
        val paths =
            listOf(
                installation.parent.resolve(INSTALLATION_SHUTDOWN_FENCE),
                installationResetFence(installation.parent),
            )
        when {
            paths.any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } -> InstalledToolAdmission.STOPPED
            paths.all { Files.notExists(it, LinkOption.NOFOLLOW_LINKS) } -> InstalledToolAdmission.AVAILABLE
            else -> InstalledToolAdmission.UNPROVEN
        }
    } catch (_: Exception) {
        InstalledToolAdmission.UNPROVEN
    }
