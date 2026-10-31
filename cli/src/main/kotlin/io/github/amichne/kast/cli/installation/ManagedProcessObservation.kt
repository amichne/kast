package io.github.amichne.kast.cli.installation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class ManagedProcessInspectionOutcome {
    CAPTURED,
    ENTRY_LIMIT_EXCEEDED,
    METADATA_UNAVAILABLE,
    SECURITY_REJECTED,
}

@Serializable
internal enum class ManagedProcessInspectionStage {
    PROCESS_ENUMERATION
}

@Serializable
internal data class ManagedProcessInspectionObservation(
    val outcome: ManagedProcessInspectionOutcome,
    val examined: Int,
    val otherUsers: Int,
    val otherExecutables: Int,
    val stateInspections: Int,
    val exited: Int,
    val selected: Int,
    val event: String = "kast_managed_host",
    val stage: ManagedProcessInspectionStage = ManagedProcessInspectionStage.PROCESS_ENUMERATION,
)

internal class ManagedProcessCounts {
    var examined = 0
    var otherUsers = 0
    var otherExecutables = 0
    var stateInspections = 0
    var exited = 0
    var selected = 0

    fun report(outcome: ManagedProcessInspectionOutcome) {
        System.err.println(
            processObservationJson.encodeToString(
                ManagedProcessInspectionObservation(
                    outcome,
                    examined,
                    otherUsers,
                    otherExecutables,
                    stateInspections,
                    exited,
                    selected,
                )
            )
        )
    }
}

private val processObservationJson = Json { encodeDefaults = true }
