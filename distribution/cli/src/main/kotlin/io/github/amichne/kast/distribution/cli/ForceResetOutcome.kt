package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ForceResetOperation {
    @SerialName("UNINSTALL_FORCE") UNINSTALL,
    @SerialName("REINSTALL_FORCE") REINSTALL,
}

@Serializable
internal enum class ForceResetStage {
    ADMISSION,
    FENCE,
    DAEMONS,
    CLEANUP,
    INSTALLATION,
    ACTIVATION,
    RECOVERY,
    FINAL_VERIFICATION,
}

@Serializable
internal enum class ForceResetFailure {
    ROOT_REJECTED,
    RESET_BUSY,
    FENCE_REJECTED,
    LAUNCH_JOB_REJECTED,
    PROCESS_OBSERVATION_REJECTED,
    PROCESS_RETIREMENT_REJECTED,
    ACTIVATION_REJECTED,
    READINESS_REJECTED,
    FILESYSTEM_REJECTED,
    INSTALLER_UNAVAILABLE,
    INSTALLER_REJECTED,
    INSTALLER_DEADLINE_EXCEEDED,
    INSTALLATION_UNVERIFIED,
}

@Serializable
internal sealed interface ForceResetOutcome {
    @Serializable
    @SerialName("REMOVED")
    data class Removed(val root: String) : ForceResetOutcome {
        val operation = ForceResetOperation.UNINSTALL
    }

    @Serializable
    @SerialName("RESET_REINSTALLED")
    data class Reinstalled(
        val installation: String,
        val version: String,
        val command: String,
        val generation: String,
    ) : ForceResetOutcome {
        val operation = ForceResetOperation.REINSTALL
    }

    @Serializable
    @SerialName("RESET_REINSTALLATION_PENDING")
    data class Pending(
        val installation: String,
        val version: String,
        val command: String,
        val failure: ForceResetFailure,
    ) : ForceResetOutcome {
        val operation = ForceResetOperation.REINSTALL
        val stage = ForceResetStage.ACTIVATION
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
    @SerialName("RESET_RECOVERY_REQUIRED")
    data class RecoveryRequired(
        val operation: ForceResetOperation,
        val stage: ForceResetStage,
        val failure: ForceResetFailure,
        val recoveryFailure: ForceResetFailure,
        val root: String,
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
