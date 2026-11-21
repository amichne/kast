package io.github.amichne.kast.cli.installation

import kotlinx.serialization.Serializable

@Serializable
internal enum class InstallationRegistryOutcome {
    RETAINED,
    SOURCE_REJECTED,
    SOURCE_LOCKED,
    DESTINATION_REJECTED,
    WRITE_REJECTED,
}

@Serializable
internal data class InstallationRegistryObservation(
    val event: String = "kast_installation_registry",
    val outcome: InstallationRegistryOutcome,
)
