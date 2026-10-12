package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ResetInventoryRejection {
    CURRENT_USER_UNAVAILABLE,
    OWNER_UNAVAILABLE,
    COMMAND_UNAVAILABLE,
    ARGUMENTS_UNAVAILABLE,
    CAPACITY_EXCEEDED,
    EFFECT_UNAVAILABLE,
}

@Serializable
internal sealed interface ResetInventoryOutcome {
    @Serializable @SerialName("OBSERVED") data class Observed(val count: Int) : ResetInventoryOutcome

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val pid: Long, val reason: ResetInventoryRejection) : ResetInventoryOutcome
}

@Serializable
internal enum class ResetInventoryType {
    PROCESS_INVENTORY
}

@Serializable
internal data class ResetInventoryObservation(
    val outcome: ResetInventoryOutcome,
    @Required val type: ResetInventoryType = ResetInventoryType.PROCESS_INVENTORY,
)

internal fun ResetInventoryObservation.asJson(): String = managementJson.encodeToString(this)
