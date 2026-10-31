package io.github.amichne.kast.cli.installation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class LegacyProfileStage {
    PROFILE_PROPERTIES
}

@Serializable
internal sealed interface LegacyProfileObservation {
    @Serializable
    @SerialName("ADMITTED")
    data class Admitted(
        val selection: LegacyProfileSelectionKind,
        val stage: LegacyProfileStage = LegacyProfileStage.PROFILE_PROPERTIES,
    ) : LegacyProfileObservation

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(
        val reason: LegacyProfileSelectionFailure,
        val stage: LegacyProfileStage = LegacyProfileStage.PROFILE_PROPERTIES,
    ) : LegacyProfileObservation
}

internal fun reportLegacyProfileSelection(observation: LegacyProfileObservation) {
    System.err.println(legacyProfileJson.encodeToString(observation))
}

private val legacyProfileJson = Json { encodeDefaults = true }
