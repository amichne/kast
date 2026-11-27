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

    /**
     * A Host keeps the settings it started with. [differences] names every changed input; [values] repeats only the
     * public settings among them, so a caller sees both sides without reading a secret or a private path.
     */
    @Serializable
    @SerialName("HOST_SETTINGS")
    data class HostSettings(
        val stage: HostSettingsComparison,
        val differences: List<String>,
        val workspace: String,
        @Required val values: List<HostSettingDifference> = emptyList(),
    ) : WorkspaceConfigurationConflict

    fun recovery(): String =
        when (this) {
            is DependencySharing ->
                "Remove the explicit $key setting at $location or set it to ${recorded.name} to reuse this workspace."
            is GradleSelection ->
                "Use the saved Gradle import selection in the caller environment and retry the request."
            is HostSettings -> {
                val stop = "kast stop --workspace ${shellArgument(workspace)}"
                when (stage) {
                    HostSettingsComparison.PREPARING ->
                        "The Host is still starting with the prepared values. Retry with those values, or wait for " +
                            "preparation, run `$stop`, and retry to start it with the requested values."
                    HostSettingsComparison.PREPARED ->
                        "The running Host keeps the prepared values. Run `$stop` and retry to start it with the " +
                            "requested values, or restore the prepared values."
                }
            }
        }
}

/** One changed public Host setting. A side that does not set the key is null. */
@Serializable data class HostSettingDifference(val key: String, val prepared: String?, val requested: String?)

private val plainShellArgument = Regex("[A-Za-z0-9_./:@%+=-]+")

/** Quotes one word so a recovery command pastes into a POSIX shell unchanged. */
fun shellArgument(value: String): String =
    if (plainShellArgument.matches(value)) value else "'" + value.replace("'", "'\\''") + "'"

@Serializable
enum class HostSettingsComparison {
    PREPARING,
    PREPARED,
}
