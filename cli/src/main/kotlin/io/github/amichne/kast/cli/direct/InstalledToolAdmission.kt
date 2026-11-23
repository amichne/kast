package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.distribution.contract.InstallationLayout
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class InstalledToolAdmission {
    AVAILABLE,
    STOPPED,
    UNPROVEN,
}

/** Installation owners keep these admission markers until their operations complete. */
internal fun observeInstalledToolAdmission(installation: Path): InstalledToolAdmission =
    try {
        val management = InstallationLayout.of(installation).management
        val paths = listOf(management.shutdownFence, management.resetFence, management.replacementRecovery)
        when {
            paths.any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } -> InstalledToolAdmission.STOPPED
            paths.all { Files.notExists(it, LinkOption.NOFOLLOW_LINKS) } -> InstalledToolAdmission.AVAILABLE
            else -> InstalledToolAdmission.UNPROVEN
        }
    } catch (_: Exception) {
        InstalledToolAdmission.UNPROVEN
    }
