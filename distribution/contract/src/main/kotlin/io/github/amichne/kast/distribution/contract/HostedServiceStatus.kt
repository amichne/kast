package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Passive management projection. Provenance identifies a host; only live admission proves compatibility. */
@Serializable
sealed interface HostedServiceStatus {
    val root: String

    @Serializable
    @SerialName("COMPATIBLE")
    data class Compatible(
        override val root: String,
        val host: String,
        val hostPid: Long,
        val hostedPluginVersion: String,
    ) : HostedServiceStatus

    @Serializable
    @SerialName("INCOMPATIBLE")
    data class Incompatible(
        override val root: String,
        val host: String,
        val hostPid: Long,
        val hostedPluginVersion: String,
        val failure: HostedCompatibilityStatusFailure,
    ) : HostedServiceStatus

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(override val root: String, val failure: HostedServiceUnavailableFailure) :
        HostedServiceStatus
}

@Serializable
enum class HostedServiceUnavailableFailure {
    CONFIGURATION_REJECTED,
    INVALID_NAME,
    INVALID_REQUEST,
    HOST_UNAVAILABLE,
    DESCRIPTOR_REJECTED,
    RESPONSE_REJECTED,
    REQUEST_TOO_LARGE,
    DEADLINE_EXCEEDED,
    TRANSPORT_REJECTED,
    SCHEMA_UNAVAILABLE,
    OPERATION_UNSUPPORTED,
    APPROVAL_REQUIRED,
    APPROVAL_REJECTED,
    COMPATIBILITY_REJECTED,
}

@Serializable
enum class HostedCompatibilityStatusField {
    IDE_BUILD,
    KOTLIN_PLUGIN_BUILD,
    KAST_PLUGIN_VERSION,
    RUNTIME_PROTOCOL_IDENTITY,
    OPERATION_REGISTRY_DIGEST,
    WIRE_SCHEMA_DIGEST,
    CAPABILITIES,
}

@Serializable
enum class HostedCompatibilityStatusSyntax {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

@Serializable
sealed interface HostedCompatibilityStatusFailure {
    @Serializable
    @SerialName("MALFORMED")
    data class Malformed(val field: HostedCompatibilityStatusField, val syntax: HostedCompatibilityStatusSyntax) :
        HostedCompatibilityStatusFailure

    @Serializable
    @SerialName("MISMATCH")
    data class Mismatch(
        val field: HostedCompatibilityStatusField,
        val expected: List<String>,
        val observed: List<String>,
    ) : HostedCompatibilityStatusFailure

    @Serializable
    @SerialName("UNKNOWN_CAPABILITY")
    data class UnknownCapability(val operationId: String) : HostedCompatibilityStatusFailure

    @Serializable
    @SerialName("UNSUPPORTED_CAPABILITY")
    data class UnsupportedCapability(val operationId: String) : HostedCompatibilityStatusFailure

    @Serializable
    @SerialName("DUPLICATE_CAPABILITY")
    data class DuplicateCapability(val operationId: String) : HostedCompatibilityStatusFailure
}
