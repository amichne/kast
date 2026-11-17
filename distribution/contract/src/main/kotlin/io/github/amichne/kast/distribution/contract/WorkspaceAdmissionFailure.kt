package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface WorkspaceAdmissionFailure

/** A request conflict carries only public settings or names of the inputs that differ. */
@Serializable
sealed interface WorkspaceConfigurationConflict : WorkspaceAdmissionFailure {
    @Serializable
    @SerialName("DEPENDENCY_SHARING")
    data class DependencySharing(
        val recorded: WorkspaceDependencySharing,
        val requested: WorkspaceDependencySharing,
        val source: ConfigurationSource,
        val location: String,
        val workspace: String,
        @Required val key: String = "KAST_WORKSPACE_DEPENDENCY_SHARING",
    ) : WorkspaceConfigurationConflict

    @Serializable
    @SerialName("GRADLE_IMPORT_SELECTION")
    data class GradleSelection(
        val requestedNames: List<String>,
        val selectedNames: List<String>,
        val source: ConfigurationSource,
    ) : WorkspaceConfigurationConflict

    @Serializable
    @SerialName("HOST_SETTINGS")
    data class HostSettings(val stage: HostSettingsComparison, val differences: List<String>) :
        WorkspaceConfigurationConflict

    fun recovery(): String =
        when (this) {
            is DependencySharing ->
                "Remove the explicit $key setting at $location or set it to ${recorded.name} to reuse this workspace."
            is GradleSelection ->
                "Use the saved Gradle import selection in the caller environment and retry the request."
            is HostSettings ->
                when (stage) {
                    HostSettingsComparison.PREPARING -> "Retry after preparation with the same Host settings."
                    HostSettingsComparison.PREPARED ->
                        "Restore the settings of the prepared Host or stop this workspace and retry."
                }
        }
}

@Serializable
enum class HostSettingsComparison {
    PREPARING,
    PREPARED,
}
