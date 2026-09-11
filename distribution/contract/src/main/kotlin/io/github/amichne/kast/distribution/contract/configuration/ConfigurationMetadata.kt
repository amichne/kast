package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.network.KastNetworkPropertyNamespace
import io.github.amichne.kast.distribution.contract.network.NetworkProperty
import kotlinx.serialization.Serializable

@Serializable
enum class ConfigurationUnit {
    NONE,
    MEBIBYTES,
    COUNT,
    CHARACTERS,
    BYTES,
    MILLISECONDS,
    PATH,
}

@Serializable
enum class ConfigurationRequiredState {
    DEFAULT_AVAILABLE,
    OWNER_DECIDES,
    DERIVED_REQUIRED,
}

@Serializable
data class ConfigurationAdmittedRange(
    val minimum: Long? = null,
    val maximum: Long? = null,
    val finiteValues: List<String> = emptyList(),
    val ownerAdmission: String? = null,
)

internal fun ConfigurationSyntax.unit(): ConfigurationUnit =
    when (this) {
        ConfigurationSyntax.HEAP,
        ConfigurationSyntax.MEMORY_MIB -> ConfigurationUnit.MEBIBYTES
        ConfigurationSyntax.WORKER_COUNT -> ConfigurationUnit.COUNT
        ConfigurationSyntax.ABSOLUTE_PATH -> ConfigurationUnit.PATH
        else -> ConfigurationUnit.NONE
    }

internal fun ConfigurationSyntax.range(): ConfigurationAdmittedRange =
    when (this) {
        ConfigurationSyntax.HEAP ->
            ConfigurationAdmittedRange(
                IndexerHeapSize.minimumMebibytes.toLong(),
                Int.MAX_VALUE.toLong(),
                ownerAdmission = "IndexerHeapSize.parse",
            )
        ConfigurationSyntax.WORKER_COUNT ->
            ConfigurationAdmittedRange(
                1,
                WorkerCountLimit.Maximum.toLong(),
                ownerAdmission = "WorkerCountLimit.admit; startup <= resident",
            )
        ConfigurationSyntax.MEMORY_MIB ->
            ConfigurationAdmittedRange(1, Long.MAX_VALUE, ownerAdmission = "WorkerMemoryReservationMiB.admit")
        ConfigurationSyntax.SWITCH -> ConfigurationAdmittedRange(finiteValues = listOf("0", "1"))
        ConfigurationSyntax.ABSOLUTE_PATH ->
            ConfigurationAdmittedRange(
                ownerAdmission = "absolute normalized path <=4096 characters; physical owner admission required"
            )
        ConfigurationSyntax.VARIABLE_NAMES ->
            ConfigurationAdmittedRange(
                0,
                64,
                ownerAdmission = "GradleImportVariableName.parse; distinct names and explicit values",
            )
        ConfigurationSyntax.EXECUTABLE_PATH ->
            ConfigurationAdmittedRange(
                0,
                32,
                ownerAdmission = "GradleImportExecutableDirectory.parse; distinct directories",
            )
        ConfigurationSyntax.OWNER_INPUT ->
            ConfigurationAdmittedRange(ownerAdmission = "named owning parser; <=32768 characters")
        ConfigurationSyntax.FAILURE_MARKER ->
            ConfigurationAdmittedRange(ownerAdmission = "always rejects; launcher source integrity marker")
    }

/** These proof inputs are emitted by launch owners, never accepted from saved user settings. */
internal object DerivedConfigurationDeclarations {
    val all: List<ConfigurationDeclaration> =
        listOf(
            derived(
                "kast.bootstrap.state.path",
                ":indexer",
                ConfigurationSyntax.ABSOLUTE_PATH,
                ConfigurationSource.JVM_PROPERTY,
            ),
            derived(
                "kast.bootstrap.attempt.id",
                ":indexer",
                ConfigurationSyntax.OWNER_INPUT,
                ConfigurationSource.JVM_PROPERTY,
            ),
            derived(
                "kast.cache.state.path",
                ":indexer",
                ConfigurationSyntax.ABSOLUTE_PATH,
                ConfigurationSource.JVM_PROPERTY,
            ),
            derived(
                "kast.network.cache.root",
                ":workspace:intellij",
                ConfigurationSyntax.ABSOLUTE_PATH,
                ConfigurationSource.JVM_PROPERTY,
            ),
            derived(
                "kast.network.workspace.root",
                ":workspace:intellij",
                ConfigurationSyntax.ABSOLUTE_PATH,
                ConfigurationSource.JVM_PROPERTY,
            ),
            derived(
                "BROKER_SERVICE_IDENTITY",
                ":app-server",
                ConfigurationSyntax.OWNER_INPUT,
                ConfigurationSource.PROCESS_ENVIRONMENT,
            ),
            derived(
                "BROKER_READINESS_FILE",
                ":app-server",
                ConfigurationSyntax.ABSOLUTE_PATH,
                ConfigurationSource.PROCESS_ENVIRONMENT,
            ),
        ) +
            NetworkProperty.entries.map { property ->
                derived(
                        KastNetworkPropertyNamespace.DAEMON_PREFIX + property.key,
                        ":workspace:intellij",
                        ConfigurationSyntax.OWNER_INPUT,
                        ConfigurationSource.JVM_PROPERTY,
                    )
                    .copy(
                        disclosure =
                            if (property == NetworkProperty.TRUST_STORE_PASSWORD)
                                ConfigurationDisclosure.SECRET_PRESENCE
                            else ConfigurationDisclosure.PUBLIC,
                        admittedRange = ConfigurationAdmittedRange(ownerAdmission = "NetworkConfiguration.parse"),
                    )
            }

    private fun derived(key: String, owner: String, syntax: ConfigurationSyntax, source: ConfigurationSource) =
        ConfigurationDeclaration(
            key = key,
            owner = owner,
            description = "Launch-owner proof input; read-only inspection, never a saved override.",
            syntax = syntax,
            scope = if (owner == ":app-server") ConfigurationScope.INSTALLATION else ConfigurationScope.WORKSPACE,
            defaultValue = null,
            defaultAuthority = "exact admitted launch owner",
            applicationBoundary = ConfigurationBoundary.NEXT_WORKER_LAUNCH,
            identityImpact = ConfigurationImpact.LAUNCH_ONLY,
            disclosure =
                if (syntax == ConfigurationSyntax.ABSOLUTE_PATH) ConfigurationDisclosure.SENSITIVE_PATH
                else ConfigurationDisclosure.PUBLIC,
            mutability = ConfigurationMutability.DERIVED,
            sources = listOf(source),
            children = emptyList(),
            requiredState = ConfigurationRequiredState.DERIVED_REQUIRED,
        )
}

@Serializable
data class ConfigurationOperationalLimit(
    val key: String,
    val owner: String,
    val value: Long,
    val unit: ConfigurationUnit,
    val scope: ConfigurationScope,
    val authority: String,
    val mutability: ConfigurationMutability = ConfigurationMutability.FIXED,
    val disclosure: ConfigurationDisclosure = ConfigurationDisclosure.PUBLIC,
    val identityImpact: ConfigurationImpact = ConfigurationImpact.LAUNCH_ONLY,
    val requiredState: ConfigurationRequiredState = ConfigurationRequiredState.DERIVED_REQUIRED,
    val sources: List<ConfigurationSource> = emptyList(),
    val children: List<ConfigurationChild> = emptyList(),
    val applicationBoundary: ConfigurationBoundary =
        when (scope) {
            ConfigurationScope.INSTALLATION -> ConfigurationBoundary.NEXT_INSTALLATION_ACTIVATION
            ConfigurationScope.HOST_PROFILE -> ConfigurationBoundary.NEXT_CONNECTION
            ConfigurationScope.WORKSPACE -> ConfigurationBoundary.NEXT_WORKER_LAUNCH
            ConfigurationScope.REQUEST -> ConfigurationBoundary.REQUEST
            ConfigurationScope.BUILD -> ConfigurationBoundary.BUILD
            ConfigurationScope.TEST -> ConfigurationBoundary.TEST
        },
    val admittedRange: ConfigurationAdmittedRange = ConfigurationAdmittedRange(value, value),
)
