package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.distribution.contract.IndexerHeapSize

internal fun ConfigurationParameter.baseDeclaration(): ConfigurationDeclaration =
    ConfigurationDeclaration(
        key,
        owner,
        when (this) {
            ConfigurationParameter.RUNTIME_STORE ->
                "Verified runtime payload store; defaults to the physical installation runtime-payloads directory."
            ConfigurationParameter.RUNTIME_DIRECTORY ->
                "Worker socket namespace owned by the physical installation state/run directory."
            ConfigurationParameter.CACHE_ROOT ->
                "Private sidecar cache; defaults to the physical installation state/cache directory."
            else -> key.lowercase().replace('_', ' ')
        },
        syntax,
        scope,
        if (this == ConfigurationParameter.INDEXER_MAX_HEAP) "${IndexerHeapSize.Default.mebibytes}m" else defaultValue,
        when (this) {
            ConfigurationParameter.INDEXER_MAX_HEAP -> "IndexerHeapSize.Default"
            ConfigurationParameter.APP_SERVER_TOOLS -> "CanonicalAgentToolDefinitions.defaultAppServerTools"
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
            ConfigurationParameter.GRADLE_JAVA_HOME,
            ConfigurationParameter.GRADLE_IMPORT_VARIABLES,
            ConfigurationParameter.GRADLE_IMPORT_PATH,
            ConfigurationParameter.GRADLE_USER_HOME,
            ConfigurationParameter.NETWORK_CONFIG,
            ConfigurationParameter.TRUST_DONOR_JAVA_HOME,
            ConfigurationParameter.IDE_CONFIG_HOME -> ConfigurationImpact.MODEL
            ConfigurationParameter.APP_SERVER_TOOLS,
            ConfigurationParameter.CODEX_HOME -> ConfigurationImpact.ROUTING
            else -> ConfigurationImpact.LAUNCH_ONLY
        },
        disclosure,
        mutability,
        acceptedSources(),
        children.sortedBy { it.name },
    )

private fun ConfigurationParameter.acceptedSources(): List<ConfigurationSource> =
    if (mutability == ConfigurationMutability.DERIVED) listOf(ConfigurationSource.PROCESS_ENVIRONMENT)
    else
        listOf(ConfigurationSource.COMMAND_LINE, ConfigurationSource.PROCESS_ENVIRONMENT) +
            (if (scope == ConfigurationScope.WORKSPACE) listOf(ConfigurationSource.SAVED_WORKSPACE) else emptyList()) +
            (if (mutability == ConfigurationMutability.USER_SETTING) listOf(ConfigurationSource.SAVED_INSTALLATION)
            else emptyList()) +
            ConfigurationSource.DEFAULT
