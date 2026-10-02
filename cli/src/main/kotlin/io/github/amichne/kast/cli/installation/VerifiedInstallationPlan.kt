package io.github.amichne.kast.cli.installation

import java.nio.file.Path

internal data class VerifiedInstallationPlan(
    val request: InstallationRequest,
    val payloadDigest: Sha256,
    val targetRoot: Path,
) {
    val configuration: Path
        get() = targetRoot.resolve("config/environment")

    fun report(activation: InstallationActivation): InstallationReport =
        InstallationReport(
            activation = activation,
            type =
                when {
                    activation == InstallationActivation.Planned -> InstallationCompletionType.PLANNED
                    request.controlOnly == InstallationSwitch.ENABLED -> InstallationCompletionType.ACTIVATED
                    else -> InstallationCompletionType.INSTALLED
                },
            semanticVersion = request.version.toString(),
            installation = targetRoot.toString(),
            controlSha256 = "sha256:${request.controlDigest.value}",
            ideaHome = request.ideaHome.value.toString(),
            ideaLaunch =
                io.github.amichne.kast.distribution.managed.SelectedIdeInstallation.resolve(request.ideaHome.value),
            changes =
                listOf(
                    "enroll-or-preserve-broker-trust",
                    "install-single-payload",
                    "write-release-local-configuration",
                    "retire-previous-app-server",
                    if (request.force == InstallationSwitch.ENABLED) "reset-installation-state-and-ownership"
                    else "retain-workspace-registry",
                    "replace-physical-installation",
                ) +
                    when (activation) {
                        InstallationActivation.Ready -> listOf("enable-app-server")
                        is InstallationActivation.Pending -> listOf("defer-app-server-activation")
                        InstallationActivation.Planned ->
                            if (request.profile == InstallationProfile.PERSISTENT) listOf("enable-app-server")
                            else emptyList()
                        InstallationActivation.NotRequested -> emptyList()
                    },
        )
}
