package io.github.amichne.kast.distribution.contract.configuration

internal fun ConfigurationParameter.baseDeclaration(): ConfigurationDeclaration =
    ConfigurationDeclaration(
        key,
        owner,
        when (this) {
            ConfigurationParameter.RUNTIME_DIRECTORY ->
                "Coordinator socket namespace owned by the physical installation state/run directory."
            else -> key.lowercase().replace('_', ' ')
        },
        syntax,
        scope,
        defaultValue,
        when (this) {
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
            ConfigurationParameter.GRADLE_USER_HOME -> ConfigurationImpact.MODEL
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
