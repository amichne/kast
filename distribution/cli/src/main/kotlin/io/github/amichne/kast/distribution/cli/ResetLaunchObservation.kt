package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ResetLaunchStage {
    INSPECT,
    BOOTOUT,
    WAIT,
}

@Serializable
internal enum class ResetLaunchObservationType {
    LAUNCH_RETIREMENT
}

@Serializable
internal data class ResetLaunchObservation(
    val stage: ResetLaunchStage,
    val variant: ResetLaunchVariant,
    val outcome: ResetCommandObservation,
    @Required val type: ResetLaunchObservationType = ResetLaunchObservationType.LAUNCH_RETIREMENT,
) {
    companion object {
        fun from(command: ResetLaunchCommand, outcome: ResetCommandObservation): ResetLaunchObservation =
            when (command) {
                is ResetLaunchCommand.Inspect ->
                    ResetLaunchObservation(ResetLaunchStage.INSPECT, command.variant, outcome)
                is ResetLaunchCommand.Bootout ->
                    ResetLaunchObservation(ResetLaunchStage.BOOTOUT, command.variant, outcome)
            }
    }
}

internal fun ResetLaunchObservation.asJson(): String = managementJson.encodeToString(this)
