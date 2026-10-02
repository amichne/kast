package io.github.amichne.kast.cli.installation

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
internal data class InstallationReport(
    val operation: String = "installation.install",
    val activation: InstallationActivation,
    val semanticVersion: String,
    val installation: String,
    val controlSha256: String,
    val hostedPluginSha256: String? = null,
    val ideaHome: String,
    val ideaLaunch: io.github.amichne.kast.distribution.managed.SelectedIdeLaunch,
    val changes: List<String>,
    @Required val type: InstallationCompletionType = InstallationCompletionType.INSTALLED,
) {
    val status: InstallationReportStatus = activation.installationStatus
}

@Serializable
internal enum class InstallationCompletionType {
    PLANNED,
    INSTALLED,
    ACTIVATED,
}
