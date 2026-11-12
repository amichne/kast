package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ForceResetOperation {
    @SerialName("UNINSTALL_FORCE") UNINSTALL
}

@Serializable
internal enum class ForceResetStage {
    ADMISSION,
    FENCE,
    DAEMONS,
    CLEANUP,
    FINAL_VERIFICATION,
}

@Serializable
internal enum class ForceResetFailure {
    ROOT_REJECTED,
    MANAGED_STATE_REQUIRES_UNINSTALL,
    RESET_BUSY,
    FENCE_REJECTED,
    LAUNCH_JOB_REJECTED,
    PROCESS_OBSERVATION_REJECTED,
    PROCESS_RETIREMENT_REJECTED,
    FILESYSTEM_REJECTED,
}

@Serializable
internal sealed interface ForceResetOutcome {
    @Serializable
    @SerialName("REMOVED")
    data class Removed(val root: String) : ForceResetOutcome {
        val operation = ForceResetOperation.UNINSTALL
    }

    @Serializable
    @SerialName("RESET_RETAINED")
    data class Retained(
        val operation: ForceResetOperation,
        val stage: ForceResetStage,
        val failure: ForceResetFailure,
        val recoveryPath: String,
    ) : ForceResetOutcome

    @Serializable
    @SerialName("RESET_REJECTED")
    data class Rejected(
        val operation: ForceResetOperation,
        val stage: ForceResetStage,
        val failure: ForceResetFailure,
    ) : ForceResetOutcome
}

internal fun ForceResetOutcome.asJson(): String = managementJson.encodeToString<ForceResetOutcome>(this)
