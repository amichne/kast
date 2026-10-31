package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class ProvisionFailure {
    INSTALLATION_UNAVAILABLE,
    PAYLOAD_REJECTED,
    SKILL_EXISTS,
    UNSUPPORTED_FILE_KIND,
    DESTINATION_UNAVAILABLE,
    PLATFORM_UNSUPPORTED,
    PROVIDER_CONFLICT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_REJECTED,
    RESOURCE_MISMATCH,
}

@Serializable
internal enum class ProvisionStage {
    NATIVE_CONTRACT_INSPECTION,
    DEPENDENCY_PREPARATION,
    INSTALLATION_LOCK,
    PAYLOAD_ADMISSION,
    DESTINATION_ADMISSION,
    STAGING,
    PUBLICATION,
    PROVIDER_INSPECTION,
    PROVIDER_INSTALLATION,
    PROVIDER_REMOVAL,
    RESOURCE_INSPECTION,
    LEGACY_REGISTRATION_INSPECTION,
    MARKETPLACE_INSPECTION,
    PLUGIN_INSPECTION,
    MARKETPLACE_REGISTRATION,
    PLUGIN_INSTALLATION,
    LEGACY_REGISTRATION_REMOVAL,
}

@Serializable
internal sealed interface ProvisionOutcome {
    @Serializable @SerialName("INSTALLED") data class Installed(val destination: String) : ProvisionOutcome

    @Serializable
    @SerialName("ALREADY_INSTALLED")
    data class AlreadyInstalled(val destination: String) : ProvisionOutcome

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val stage: ProvisionStage, val reason: ProvisionFailure, val destination: String) :
        ProvisionOutcome

    @Serializable
    @SerialName("RECOVERY_REQUIRED")
    data class RecoveryRequired(val stage: ProvisionStage, val reason: ProvisionFailure, val destination: String) :
        ProvisionOutcome
}
