package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.RecoveryRemovalFailure
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class UninstallArtifact {
    CONTROL_PAYLOAD,
    HOST_PLUGIN,
    HOME_CONFIGURATION,
    USER_STATE,
    RECOVERY_BUNDLE,
    ACTIVATION_LOCK,
    MANAGED_ROOT,
    CODEX_MCP_REGISTRATION,
    CODEX_APP_SERVER_REGISTRATION,
    COPILOT_REGISTRATION,
    PI_REGISTRATION,
    PUBLIC_EXECUTABLE,
    MANAGEMENT_LOCK,
    MANAGEMENT_RECEIPT,
    RETIREMENT_JOURNAL,
}

@Serializable
internal enum class UninstallCleanupFailure {
    OWNERSHIP_UNPROVEN,
    FILESYSTEM_REJECTED,
}

/** Bounded cleanup evidence names only owned artifact categories, never configuration contents. */
@Serializable
internal sealed interface UninstallCleanupObservation {
    val artifact: UninstallArtifact

    @Serializable
    @SerialName("STARTED")
    data class Started(override val artifact: UninstallArtifact) : UninstallCleanupObservation

    @Serializable
    @SerialName("COMPLETED")
    data class Completed(override val artifact: UninstallArtifact) : UninstallCleanupObservation

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(override val artifact: UninstallArtifact, val failure: UninstallCleanupFailure) :
        UninstallCleanupObservation

    @Serializable
    @SerialName("CONTROL_CHILD_REJECTED")
    data class ControlChildRejected(val exitCode: Int) : UninstallCleanupObservation {
        @Required override val artifact: UninstallArtifact = UninstallArtifact.CONTROL_PAYLOAD
    }

    @Serializable
    @SerialName("CONTROL_PAYLOAD_RETAINED")
    class ControlPayloadRetained : UninstallCleanupObservation {
        @Required override val artifact: UninstallArtifact = UninstallArtifact.CONTROL_PAYLOAD
    }

    @Serializable
    @SerialName("HOME_CONFIGURATION_REJECTED")
    data class HomeConfigurationRejected(val failure: HomeConfigurationCleanupFailure) : UninstallCleanupObservation {
        @Required override val artifact: UninstallArtifact = UninstallArtifact.HOME_CONFIGURATION
    }

    @Serializable
    @SerialName("HOST_REJECTED")
    data class HostRejected(val failure: HostPluginCleanupFailure) : UninstallCleanupObservation {
        @Required override val artifact: UninstallArtifact = UninstallArtifact.HOST_PLUGIN
    }

    @Serializable
    @SerialName("RECOVERY_REJECTED")
    data class RecoveryRejected(val failure: RecoveryRemovalFailure) : UninstallCleanupObservation {
        @Required override val artifact: UninstallArtifact = UninstallArtifact.RECOVERY_BUNDLE
    }

    @Serializable
    @SerialName("USER_STATE_REJECTED")
    data class UserStateRejected(val failure: UserStateCleanupFailure) : UninstallCleanupObservation {
        @Required override val artifact: UninstallArtifact = UninstallArtifact.USER_STATE
    }
}

internal fun HarnessConnection.uninstallArtifact(): UninstallArtifact =
    when (this) {
        HarnessConnection.CODEX_MCP -> UninstallArtifact.CODEX_MCP_REGISTRATION
        HarnessConnection.CODEX_APP_SERVER -> UninstallArtifact.CODEX_APP_SERVER_REGISTRATION
        HarnessConnection.COPILOT -> UninstallArtifact.COPILOT_REGISTRATION
        HarnessConnection.PI -> UninstallArtifact.PI_REGISTRATION
    }
