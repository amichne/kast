package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ResetProcessStage {
    REVALIDATE,
    CHILDREN,
    SIGNAL,
    WAIT,
    VERIFY,
}

@Serializable
internal enum class ResetProcessResult {
    IDENTITY_VERIFIED,
    RETIRED,
    INCARNATION_CHANGED,
    OBSERVATION_REJECTED,
    IDENTITY_REJECTED,
    SIGNAL_REJECTED,
    DEADLINE_EXCEEDED,
    WAIT_REJECTED,
    INTERRUPTED,
}

@Serializable
internal enum class ResetProcessObservationType {
    PROCESS_RETIREMENT
}

@Serializable
internal data class ResetProcessObservation(
    val pid: Long,
    val stage: ResetProcessStage,
    val outcome: ResetProcessResult,
    @Required val type: ResetProcessObservationType = ResetProcessObservationType.PROCESS_RETIREMENT,
)

internal fun ResetProcessObservation.asJson(): String = managementJson.encodeToString(this)
