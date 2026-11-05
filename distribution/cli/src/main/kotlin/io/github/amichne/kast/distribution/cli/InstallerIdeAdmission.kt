package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.IdeLaunchFailure
import io.github.amichne.kast.distribution.managed.IdePluginRootFailure
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.distribution.managed.SelectedIdePluginRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class InstallerIdeInputFailure {
    HOME_UNAVAILABLE,
    IDEA_HOME_UNAVAILABLE,
    INVALID_HOME,
    INVALID_IDEA_HOME,
}

internal sealed interface InstallerIdeAdmission {
    class Admitted private constructor(val launch: SelectedIdeLaunch.Resolved, val pluginRoot: SelectedIdePluginRoot) :
        InstallerIdeAdmission {
        companion object {
            fun forLaunch(launch: SelectedIdeLaunch.Resolved, home: Path): InstallerIdeAdmission =
                when (val profile = SelectedIdeInstallation.pluginRoot(launch, home)) {
                    is Refinement.Refined -> Admitted(launch, profile.value)
                    is Refinement.Rejected -> ProfileRejected(profile.failure)
                }
        }
    }

    data class InputRejected(val reason: InstallerIdeInputFailure) : InstallerIdeAdmission

    data class LaunchRejected(val reason: IdeLaunchFailure) : InstallerIdeAdmission

    data class ProfileRejected(val reason: IdePluginRootFailure) : InstallerIdeAdmission
}

/** Read-only vendor/profile admission precedes reset; it grants no process or Host readiness claim. */
internal fun admitInstallerIde(environment: Map<String, String>): InstallerIdeAdmission {
    val rawHome =
        environment["HOME"] ?: return InstallerIdeAdmission.InputRejected(InstallerIdeInputFailure.HOME_UNAVAILABLE)
    val rawIdea =
        environment["KAST_INSTALL_IDEA_HOME"]
            ?: return InstallerIdeAdmission.InputRejected(InstallerIdeInputFailure.IDEA_HOME_UNAVAILABLE)
    val home =
        when (val path = InstallerPath.parse(rawHome, InstallerIdeInputFailure.INVALID_HOME)) {
            is Refinement.Refined -> path.value
            is Refinement.Rejected -> return InstallerIdeAdmission.InputRejected(path.failure)
        }
    val idea =
        when (val path = InstallerPath.parse(rawIdea, InstallerIdeInputFailure.INVALID_IDEA_HOME)) {
            is Refinement.Refined -> path.value
            is Refinement.Rejected -> return InstallerIdeAdmission.InputRejected(path.failure)
        }
    return when (val launch = SelectedIdeInstallation.resolve(idea.value)) {
        is SelectedIdeLaunch.Resolved -> InstallerIdeAdmission.Admitted.forLaunch(launch, home.value)
        is SelectedIdeLaunch.Unavailable -> InstallerIdeAdmission.LaunchRejected(launch.reason)
    }
}

private class InstallerPath private constructor(val value: Path) {
    companion object {
        fun parse(raw: String, failure: InstallerIdeInputFailure): Refinement<InstallerPath, InstallerIdeInputFailure> =
            try {
                val path = Path.of(raw)
                if (path.isAbsolute && path.normalize() == path) Refinement.Refined(InstallerPath(path))
                else Refinement.Rejected(failure)
            } catch (_: InvalidPathException) {
                Refinement.Rejected(failure)
            }
    }
}

internal fun preflightInstallerIde(environment: Map<String, String>): SelectedIdeLaunch.Resolved =
    when (val admitted = admitInstallerIde(environment)) {
        is InstallerIdeAdmission.Admitted -> admitted.launch
        is InstallerIdeAdmission.InputRejected ->
            throw ManagementRejected("installer-idea-preflight", admitted.reason.name)
        is InstallerIdeAdmission.LaunchRejected ->
            throw ManagementRejected("installer-idea-preflight", admitted.reason.name)
        is InstallerIdeAdmission.ProfileRejected ->
            throw ManagementRejected("installer-idea-preflight", admitted.reason.name)
    }
