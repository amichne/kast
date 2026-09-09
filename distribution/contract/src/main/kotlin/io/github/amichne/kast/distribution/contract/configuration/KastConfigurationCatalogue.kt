package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import kotlinx.serialization.Serializable

@Serializable enum class ConfigurationScope { INSTALLATION, HOST_PROFILE, WORKSPACE, REQUEST, BUILD, TEST }
@Serializable enum class ConfigurationSource { COMMAND_LINE, PROCESS_ENVIRONMENT, SAVED_WORKSPACE, SAVED_INSTALLATION, DEFAULT, JVM_PROPERTY }
@Serializable enum class ConfigurationBoundary { NEXT_INSTALLATION_ACTIVATION, NEXT_CONNECTION, NEXT_WORKER_LAUNCH, REQUEST, BUILD, TEST }
@Serializable enum class ConfigurationImpact { LAUNCH_ONLY, ROUTING, MODEL }
@Serializable enum class ConfigurationDisclosure { PUBLIC, SENSITIVE_PATH, SECRET_PRESENCE }
@Serializable enum class ConfigurationMutability { USER_SETTING, DERIVED, TEST_ONLY, BUILD_SETTING, FIXED }
@Serializable enum class ConfigurationSyntax { ABSOLUTE_PATH, HEAP, SWITCH, WORKER_COUNT, MEMORY_MIB, OWNER_INPUT, VARIABLE_NAMES, EXECUTABLE_PATH, FAILURE_MARKER }
@Serializable enum class ConfigurationChild { BROKER, SIDECAR }

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
    val requiredState: ConfigurationRequiredState = if (defaultValue == null) ConfigurationRequiredState.OWNER_DECIDES else ConfigurationRequiredState.DEFAULT_AVAILABLE,
)

/** Closed identity: arbitrary caller keys never become admitted configuration identities. */
enum class ConfigurationParameter(
    val key: String,
    internal val syntax: ConfigurationSyntax,
    internal val scope: ConfigurationScope = ConfigurationScope.WORKSPACE,
    internal val owner: String = ":distribution:contract",
    internal val defaultValue: String? = null,
    internal val disclosure: ConfigurationDisclosure = ConfigurationDisclosure.PUBLIC,
    internal val mutability: ConfigurationMutability = ConfigurationMutability.USER_SETTING,
    internal val children: Set<ConfigurationChild> = emptySet(),
) {
    INSTALL_ROOT("KAST_INSTALL_ROOT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION, children = setOf(ConfigurationChild.BROKER)),
    CONFIGURATION_FILE("KAST_CONFIGURATION_FILE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION,
        ":distribution:contract", disclosure = ConfigurationDisclosure.SENSITIVE_PATH,
        mutability = ConfigurationMutability.DERIVED, children = setOf(ConfigurationChild.BROKER)),
    RUNTIME_ARCHIVE("KAST_RUNTIME_ARCHIVE", ConfigurationSyntax.ABSOLUTE_PATH, children = setOf(ConfigurationChild.BROKER)),
    RUNTIME_STORE("KAST_RUNTIME_STORE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION, children = setOf(ConfigurationChild.BROKER)),
    RUNTIME_DIRECTORY("KAST_RUNTIME_DIRECTORY", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION, children = setOf(ConfigurationChild.BROKER)),
    CACHE_ROOT("KAST_CACHE_ROOT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION, children = setOf(ConfigurationChild.BROKER)),
    INDEXER_MAX_HEAP(IndexerHeapSize.SETTING, ConfigurationSyntax.HEAP, children = setOf(ConfigurationChild.BROKER)),
    WORKER_RESIDENT_LIMIT("KAST_WORKER_RESIDENT_LIMIT", ConfigurationSyntax.WORKER_COUNT, ConfigurationScope.INSTALLATION, ":app-server", "1", children = setOf(ConfigurationChild.BROKER)),
    WORKER_STARTUP_LIMIT("KAST_WORKER_STARTUP_LIMIT", ConfigurationSyntax.WORKER_COUNT, ConfigurationScope.INSTALLATION, ":app-server", "1", children = setOf(ConfigurationChild.BROKER)),
    WORKER_AGGREGATE_MIB("KAST_WORKER_AGGREGATE_MIB", ConfigurationSyntax.MEMORY_MIB, ConfigurationScope.INSTALLATION, ":app-server", "32768", children = setOf(ConfigurationChild.BROKER)),
    WORKER_NATIVE_MIB("KAST_WORKER_NATIVE_MIB", ConfigurationSyntax.MEMORY_MIB, ConfigurationScope.INSTALLATION, ":app-server", "1024", children = setOf(ConfigurationChild.BROKER)),
    WORKER_GRADLE_MIB("KAST_WORKER_GRADLE_MIB", ConfigurationSyntax.MEMORY_MIB, ConfigurationScope.INSTALLATION, ":app-server", "2048", children = setOf(ConfigurationChild.BROKER)),
    ENABLE_LAUNCHD("KAST_ENABLE_LAUNCHD", ConfigurationSyntax.SWITCH, owner = ":cli", defaultValue = "0", children = setOf(ConfigurationChild.BROKER)),
    ENABLE_APP_SERVER("KAST_ENABLE_APP_SERVER", ConfigurationSyntax.SWITCH, ConfigurationScope.HOST_PROFILE, ":app-server", "1"),
    APP_SERVER_TOOLS("KAST_APP_SERVER_TOOLS", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.HOST_PROFILE, ":app-server"),
    REAL_CODEX_EXECUTABLE("KAST_REAL_CODEX_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.HOST_PROFILE, ":app-server"),
    CODEX_DESKTOP_EXECUTABLE("KAST_CODEX_DESKTOP_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.HOST_PROFILE, ":app-server"),
    CODEX_EXECUTABLE("CODEX_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.HOST_PROFILE, ":app-server"),
    CODEX_HOME("CODEX_HOME", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.HOST_PROFILE, ":app-server", disclosure = ConfigurationDisclosure.SENSITIVE_PATH),
    GRADLE_JAVA_HOME("KAST_GRADLE_JAVA_HOME", ConfigurationSyntax.ABSOLUTE_PATH, children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    GRADLE_IMPORT_VARIABLES("KAST_GRADLE_IMPORT_VARIABLES", ConfigurationSyntax.VARIABLE_NAMES, defaultValue = "", children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    GRADLE_IMPORT_PATH("KAST_GRADLE_IMPORT_PATH", ConfigurationSyntax.EXECUTABLE_PATH, defaultValue = "", children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    GRADLE_USER_HOME("GRADLE_USER_HOME", ConfigurationSyntax.ABSOLUTE_PATH, disclosure = ConfigurationDisclosure.SENSITIVE_PATH, children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    NETWORK_CONFIG("KAST_NETWORK_CONFIG", ConfigurationSyntax.ABSOLUTE_PATH, owner = ":distribution:managed", disclosure = ConfigurationDisclosure.SENSITIVE_PATH, children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    TRUST_DONOR_JAVA_HOME("KAST_TRUST_DONOR_JAVA_HOME", ConfigurationSyntax.ABSOLUTE_PATH, owner = ":distribution:managed", disclosure = ConfigurationDisclosure.SENSITIVE_PATH, children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    IDE_CONFIG_HOME("KAST_IDE_CONFIG_HOME", ConfigurationSyntax.ABSOLUTE_PATH, owner = ":distribution:managed", disclosure = ConfigurationDisclosure.SENSITIVE_PATH, children = setOf(ConfigurationChild.BROKER, ConfigurationChild.SIDECAR)),
    INSTALL_MIGRATE_CONFIGURATION("KAST_INSTALL_MIGRATE_CONFIGURATION", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION, ":distribution:contract", mutability = ConfigurationMutability.DERIVED),
    ACCEPTANCE_IDEA_HOME("KAST_ACCEPTANCE_IDEA_HOME", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    ACCEPTANCE_PROFILE("KAST_ACCEPTANCE_PROFILE", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    ACCEPTANCE_JAVA_EXECUTABLE("KAST_ACCEPTANCE_JAVA_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    ACCEPTANCE_NODE_EXECUTABLE("KAST_ACCEPTANCE_NODE_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    SAVED_CONFIGURATION_FAILURE("KAST_SAVED_CONFIGURATION_FAILURE", ConfigurationSyntax.FAILURE_MARKER, ConfigurationScope.INSTALLATION, ":cli", mutability = ConfigurationMutability.DERIVED),
    BIN_DIR("KAST_BIN_DIR", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION),
    VERSION("KAST_VERSION", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.INSTALLATION),
    REPOSITORY("KAST_REPOSITORY", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.INSTALLATION),
    RELEASE_BASE_URL("KAST_RELEASE_BASE_URL", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.INSTALLATION),
    INSTALL_IDEA_HOME("KAST_INSTALL_IDEA_HOME", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION),
    INSTALL_IDEA_SEARCH_ROOT("KAST_INSTALL_IDEA_SEARCH_ROOT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION),
    ASCII("KAST_ASCII", ConfigurationSyntax.SWITCH, ConfigurationScope.INSTALLATION, defaultValue = "0"),
    APP_JAR("KAST_APP_JAR", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.INSTALLATION),
    JVM_OPTIONS("KAST_OPTS", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.INSTALLATION, ":cli", disclosure = ConfigurationDisclosure.SECRET_PRESENCE, mutability = ConfigurationMutability.DERIVED),
    RUNTIME_BASE_URL("KAST_RUNTIME_BASE_URL", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    LOCAL_PREFIX("KAST_LOCAL_PREFIX", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    LOCAL_CONTROL_PRODUCT("KAST_LOCAL_CONTROL_PRODUCT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    LOCAL_RUNTIME_ARCHIVE("KAST_LOCAL_RUNTIME_ARCHIVE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    LOCAL_JAVA_EXECUTABLE("KAST_LOCAL_JAVA_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    LOCAL_JAVA_HOME("KAST_LOCAL_JAVA_HOME", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    INSTALL_PROCESS_TABLE_COMMAND("KAST_INSTALL_PROCESS_TABLE_COMMAND", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    INSTALL_PROCESS_KILL_COMMAND("KAST_INSTALL_PROCESS_KILL_COMMAND", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    INSTALLED_PRODUCT("KAST_INSTALLED_PRODUCT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    CONTROL_ARCHIVE("KAST_CONTROL_ARCHIVE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    SEMANTIC_RUNTIME_ARCHIVE("KAST_SEMANTIC_RUNTIME_ARCHIVE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    INSTALLED_REPORT_DIRECTORY("KAST_INSTALLED_REPORT_DIRECTORY", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    PROJECT_ROOT("KAST_PROJECT_ROOT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    ACCEPTANCE_CODEX_EXECUTABLE("KAST_ACCEPTANCE_CODEX_EXECUTABLE", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    CODEX_ACCEPTANCE_VERSION("KAST_CODEX_ACCEPTANCE_VERSION", ConfigurationSyntax.OWNER_INPUT, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    SESSION_ROOT("KAST_SESSION_ROOT", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.TEST, ":build-logic", mutability = ConfigurationMutability.TEST_ONLY),
    OBSERVER_CHROME("KAST_OBSERVER_CHROME", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    OBSERVER_PANDOC("KAST_OBSERVER_PANDOC", ConfigurationSyntax.ABSOLUTE_PATH, ConfigurationScope.BUILD, ":build-logic", mutability = ConfigurationMutability.BUILD_SETTING),
    ;

    fun declaration(): ConfigurationDeclaration = ConfigurationDeclaration(
        key, owner, when (this) {
            RUNTIME_STORE -> "Verified runtime payload store; defaults to the physical installation runtime-payloads directory."
            RUNTIME_DIRECTORY -> "Worker socket namespace owned by the physical installation state/run directory."
            CACHE_ROOT -> "Private sidecar cache; defaults to the physical installation state/cache directory."
            else -> key.lowercase().replace('_', ' ')
        }, syntax, scope,
        if (this == INDEXER_MAX_HEAP) "${IndexerHeapSize.Default.mebibytes}m" else defaultValue,
        when (this) {
            INDEXER_MAX_HEAP -> "IndexerHeapSize.Default"
            APP_SERVER_TOOLS -> "CanonicalAgentToolDefinitions.defaultAppServerTools"
            else -> if (defaultValue == null) "owning boundary; no catalogue fallback" else "declared literal"
        },
        when (scope) {
            ConfigurationScope.INSTALLATION -> ConfigurationBoundary.NEXT_INSTALLATION_ACTIVATION
            ConfigurationScope.HOST_PROFILE -> ConfigurationBoundary.NEXT_CONNECTION
            ConfigurationScope.WORKSPACE -> ConfigurationBoundary.NEXT_WORKER_LAUNCH
            ConfigurationScope.REQUEST -> ConfigurationBoundary.REQUEST
            ConfigurationScope.BUILD -> ConfigurationBoundary.BUILD
            ConfigurationScope.TEST -> ConfigurationBoundary.TEST
        },
        when (this) {
            GRADLE_JAVA_HOME, GRADLE_IMPORT_VARIABLES, GRADLE_IMPORT_PATH, GRADLE_USER_HOME, NETWORK_CONFIG,
            TRUST_DONOR_JAVA_HOME, IDE_CONFIG_HOME -> ConfigurationImpact.MODEL
            APP_SERVER_TOOLS, CODEX_HOME -> ConfigurationImpact.ROUTING
            else -> ConfigurationImpact.LAUNCH_ONLY
        },
        disclosure, mutability,
        if (mutability == ConfigurationMutability.DERIVED) listOf(ConfigurationSource.PROCESS_ENVIRONMENT)
        else listOf(ConfigurationSource.COMMAND_LINE, ConfigurationSource.PROCESS_ENVIRONMENT) +
            (if (scope == ConfigurationScope.WORKSPACE) listOf(ConfigurationSource.SAVED_WORKSPACE) else emptyList()) +
            (if (mutability == ConfigurationMutability.USER_SETTING) listOf(ConfigurationSource.SAVED_INSTALLATION) else emptyList()) + ConfigurationSource.DEFAULT,
        children.sortedBy { it.name },
    )
}

object KastConfigurationCatalogue {
    val declarations: List<ConfigurationDeclaration> = (ConfigurationParameter.entries.map { it.declaration() } + DerivedConfigurationDeclarations.all).sortedBy { it.key }
    fun parameter(key: String): ConfigurationParameter? = ConfigurationParameter.entries.singleOrNull { it.key == key }
}
