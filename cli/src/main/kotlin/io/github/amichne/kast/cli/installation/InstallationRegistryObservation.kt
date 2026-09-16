package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.WorkspaceRegistryRetention
import io.github.amichne.kast.appserver.WorkspaceRegistryRetentionFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

internal fun reportRegistryRetention(retention: WorkspaceRegistryRetention) {
    val outcome =
        when (retention) {
            WorkspaceRegistryRetention.Retained -> InstallationRegistryOutcome.RETAINED
            is WorkspaceRegistryRetention.Rejected ->
                when (retention.failure) {
                    WorkspaceRegistryRetentionFailure.SOURCE_REJECTED -> InstallationRegistryOutcome.SOURCE_REJECTED
                    WorkspaceRegistryRetentionFailure.SOURCE_LOCKED -> InstallationRegistryOutcome.SOURCE_LOCKED
                    WorkspaceRegistryRetentionFailure.DESTINATION_REJECTED ->
                        InstallationRegistryOutcome.DESTINATION_REJECTED
                    WorkspaceRegistryRetentionFailure.WRITE_REJECTED -> InstallationRegistryOutcome.WRITE_REJECTED
                }
        }
    System.err.println(
        Json { encodeDefaults = true }
            .encodeToString(
                InstallationRegistryObservation.serializer(),
                InstallationRegistryObservation(outcome = outcome),
            )
    )
}
