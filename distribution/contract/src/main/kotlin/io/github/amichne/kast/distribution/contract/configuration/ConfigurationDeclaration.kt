package io.github.amichne.kast.distribution.contract.configuration

import kotlinx.serialization.Serializable

@Serializable
enum class ConfigurationScope {
    INSTALLATION,
    HOST_PROFILE,
    WORKSPACE,
    REQUEST,
    BUILD,
    TEST,
}

@Serializable
enum class ConfigurationSource {
    COMMAND_LINE,
    PROCESS_ENVIRONMENT,
    SAVED_WORKSPACE,
    SAVED_INSTALLATION,
    DEFAULT,
    JVM_PROPERTY,
}

@Serializable
enum class ConfigurationBoundary {
    NEXT_INSTALLATION_ACTIVATION,
    NEXT_CONNECTION,
    NEXT_WORKER_LAUNCH,
    REQUEST,
    BUILD,
    TEST,
}

@Serializable
enum class ConfigurationImpact {
    LAUNCH_ONLY,
    ROUTING,
    MODEL,
}

@Serializable
enum class ConfigurationDisclosure {
    PUBLIC,
    SENSITIVE_PATH,
    SECRET_PRESENCE,
}

@Serializable
enum class ConfigurationMutability {
    USER_SETTING,
    DERIVED,
    TEST_ONLY,
    BUILD_SETTING,
    FIXED,
}

@Serializable
enum class ConfigurationSyntax {
    ABSOLUTE_PATH,
    SWITCH,
    OWNER_INPUT,
    VARIABLE_NAMES,
    EXECUTABLE_PATH,
    FAILURE_MARKER,
}

@Serializable
enum class ConfigurationChild {
    BROKER,
    SIDECAR,
}

/** The catalogue describes owner inputs; OWNER_INPUT still requires the named owner's semantic admission. */
@Serializable
data class ConfigurationDeclaration(
    val key: String,
    val owner: String,
    val description: String,
    val syntax: ConfigurationSyntax,
    val scope: ConfigurationScope,
    val defaultValue: String?,
    val defaultAuthority: String,
    val applicationBoundary: ConfigurationBoundary,
    val identityImpact: ConfigurationImpact,
    val disclosure: ConfigurationDisclosure,
    val mutability: ConfigurationMutability,
    val sources: List<ConfigurationSource>,
    val children: List<ConfigurationChild>,
    val unit: ConfigurationUnit = syntax.unit(),
    val admittedRange: ConfigurationAdmittedRange = syntax.range(),
    val requiredState: ConfigurationRequiredState =
        if (defaultValue == null) ConfigurationRequiredState.OWNER_DECIDES
        else ConfigurationRequiredState.DEFAULT_AVAILABLE,
)
