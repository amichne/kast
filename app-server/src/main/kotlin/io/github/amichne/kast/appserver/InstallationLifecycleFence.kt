package io.github.amichne.kast.appserver

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A transition marker survives retirement and state replacement. Unknown observation fails closed. */
internal enum class InstallationLifecycleStartAdmission { AVAILABLE, TRANSITION_IN_PROGRESS, OBSERVATION_REJECTED }

internal object InstallationLifecycleFence {
    fun observe(installationRoot: Path): InstallationLifecycleStartAdmission = try {
        Files.readAttributes(installationRoot.resolve(".lifecycle-transition.json"), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS
    } catch (_: NoSuchFileException) {
        InstallationLifecycleStartAdmission.AVAILABLE
    } catch (_: IOException) {
        InstallationLifecycleStartAdmission.OBSERVATION_REJECTED
    } catch (_: SecurityException) {
        InstallationLifecycleStartAdmission.OBSERVATION_REJECTED
    }
}
