package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal sealed interface ResetObservation {
    @Serializable
    @SerialName("STARTED")
    data class Started(
        val operation: ForceResetOperation,
        val stage: ForceResetStage,
        val component: String = "kast-reset",
    ) : ResetObservation

    @Serializable
    @SerialName("COMPLETED")
    data class Completed(
        val operation: ForceResetOperation,
        val stage: ForceResetStage,
        val component: String = "kast-reset",
    ) : ResetObservation

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(
        val operation: ForceResetOperation,
        val stage: ForceResetStage,
        val failure: ForceResetFailure,
        val component: String = "kast-reset",
    ) : ResetObservation
}

internal fun ResetObservation.asJson(): String = managementJson.encodeToString<ResetObservation>(this)
