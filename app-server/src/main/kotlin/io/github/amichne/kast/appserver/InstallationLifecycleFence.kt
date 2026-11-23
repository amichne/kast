package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.InstallationLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A transition marker survives retirement and state replacement. Unknown observation fails closed. */
internal enum class InstallationLifecycleStartAdmission {
    AVAILABLE,
    TRANSITION_IN_PROGRESS,
    OBSERVATION_REJECTED,
}

internal object InstallationLifecycleFence {
    fun observe(installationRoot: Path): InstallationLifecycleStartAdmission =
        try {
            if (
                present(InstallationLayout.of(installationRoot).management.shutdownFence) ||
                    resetPresent(installationRoot)
            )
                InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS
            else InstallationLifecycleStartAdmission.AVAILABLE
        } catch (_: IOException) {
            InstallationLifecycleStartAdmission.OBSERVATION_REJECTED
        } catch (_: SecurityException) {
            InstallationLifecycleStartAdmission.OBSERVATION_REJECTED
        }

    private fun resetPresent(installationRoot: Path): Boolean {
        val marker = InstallationLayout.of(installationRoot).management.resetFence
        if (!present(marker)) return false
        return when (ResetActivationAdmission.observe(marker, installationRoot)) {
            ResetActivationLease.HELD -> false
            ResetActivationLease.ABSENT,
            ResetActivationLease.REJECTED -> true
        }
    }

    private fun present(path: Path): Boolean =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: NoSuchFileException) {
            false
        }
}
