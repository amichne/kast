package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.ReadLimitParameter

internal fun readLimitDescription(parameter: ReadLimitParameter): String =
    readLimitDescriptions[parameter]?.let { "$it IDE JVM property: ${parameter.propertyKey}." }
        ?: ("${parameter.name.lowercase().replace('_', ' ')}; " +
            "also available to the IDE as ${parameter.propertyKey}.")

private val readLimitDescriptions =
    mapOf(
        ReadLimitParameter.SEMANTIC_MILLIS to
            "Default elapsed allowance when executionBudget.maxElapsedMs is absent. This is a ceiling, not a wait.",
        ReadLimitParameter.SEMANTIC_WORK to
            ("Default semantic work allowance when executionBudget.maxWorkUnits is absent. " +
                "It does not increase response size."),
        ReadLimitParameter.SEMANTIC_RESULTS to
            ("Default semantic result allowance when executionBudget.maxResults is absent. " +
                "It does not establish completeness."),
        ReadLimitParameter.SEMANTIC_RETURNED_BYTES to
            ("Default encoded response allowance when executionBudget.maxReturnedBytes is absent. " +
                "It does not increase execution capacity."),
        ReadLimitParameter.EXECUTION_MAX_MILLIS to
            ("Operator ceiling for default and requested elapsed allowances. " +
                "Remaining host time can reduce the admitted grant."),
        ReadLimitParameter.EXECUTION_MAX_WORK to
            "Operator ceiling for default and requested work allowances. It does not set the default grant.",
        ReadLimitParameter.EXECUTION_MAX_RESULTS to
            "Operator ceiling for default and requested result allowances. It does not set the default grant.",
        ReadLimitParameter.EXECUTION_MAX_RETURNED_BYTES to
            ("Operator ceiling for default and requested response bytes. " +
                "Transport capacity can reduce the admitted grant."),
        ReadLimitParameter.QUERY_CHECKPOINT_BYTES to
            "Maximum charged bytes per query checkpoint. This storage limit is separate from response pagination.",
        ReadLimitParameter.QUERY_CONTINUATION_BYTES to
            "Maximum charged bytes per query continuation store. This accounting is not a heap measurement.",
        ReadLimitParameter.QUERY_CONTINUATION_ENTRIES to "Maximum entries per query continuation store.",
        ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS to
            "Maximum query continuation age. Output pagination does not renew this lifetime.",
    )

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
