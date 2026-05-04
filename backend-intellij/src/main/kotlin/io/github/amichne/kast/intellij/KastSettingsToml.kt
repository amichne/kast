package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.KastConfig

internal fun KastSettingsState.toWorkspaceToml(defaults: KastConfig = KastConfig.defaults()): String {
    val sections = buildList {
        add(
            tomlSection(
                "server",
                "maxResults" to serverMaxResults.changedFrom(defaults.server.maxResults),
                "requestTimeoutMillis" to serverRequestTimeoutMillis.changedFrom(defaults.server.requestTimeoutMillis),
                "maxConcurrentRequests" to serverMaxConcurrentRequests.changedFrom(defaults.server.maxConcurrentRequests),
            ),
        )
        add(
            tomlSection(
                "indexing",
                "phase2Enabled" to indexingPhase2Enabled.changedFrom(defaults.indexing.phase2Enabled),
                "phase2BatchSize" to indexingPhase2BatchSize.changedFrom(defaults.indexing.phase2BatchSize),
                "identifierIndexWaitMillis" to indexingIdentifierIndexWaitMillis.changedFrom(defaults.indexing.identifierIndexWaitMillis),
                "referenceBatchSize" to indexingReferenceBatchSize.changedFrom(defaults.indexing.referenceBatchSize),
            ),
        )
        add(
            tomlSection(
                "indexing.remote",
                "enabled" to indexingRemoteEnabled.changedFrom(defaults.indexing.remote.enabled),
                "sourceIndexUrl" to indexingRemoteSourceIndexUrl.changedFrom(defaults.indexing.remote.sourceIndexUrl),
            ),
        )
        add(
            tomlSection(
                "cache",
                "enabled" to cacheEnabled.changedFrom(defaults.cache.enabled),
                "writeDelayMillis" to cacheWriteDelayMillis.changedFrom(defaults.cache.writeDelayMillis),
                "sourceIndexSaveDelayMillis" to cacheSourceIndexSaveDelayMillis.changedFrom(defaults.cache.sourceIndexSaveDelayMillis),
            ),
        )
        add(tomlSection("watcher", "debounceMillis" to watcherDebounceMillis.changedFrom(defaults.watcher.debounceMillis)))
        add(
            tomlSection(
                "gradle",
                "toolingApiTimeoutMillis" to gradleToolingApiTimeoutMillis.changedFrom(defaults.gradle.toolingApiTimeoutMillis),
                "maxIncludedProjects" to gradleMaxIncludedProjects.changedFrom(defaults.gradle.maxIncludedProjects),
            ),
        )
        add(
            tomlSection(
                "telemetry",
                "enabled" to telemetryEnabled.changedFrom(defaults.telemetry.enabled),
                "scopes" to telemetryScopes.changedFrom(defaults.telemetry.scopes),
                "detail" to telemetryDetail.changedFrom(defaults.telemetry.detail),
                "outputFile" to telemetryOutputFile.changedFrom(defaults.telemetry.outputFile),
            ),
        )
        add(
            tomlSection(
                "backends.standalone",
                "enabled" to backendsStandaloneEnabled.changedFrom(defaults.backends.standalone.enabled),
                "runtimeLibsDir" to backendsStandaloneRuntimeLibsDir.changedFrom(defaults.backends.standalone.runtimeLibsDir),
            ),
        )
        add(tomlSection("backends.intellij", "enabled" to backendsIntellijEnabled.changedFrom(defaults.backends.intellij.enabled)))
    }.filter(String::isNotBlank)

    if (sections.isEmpty()) return ""
    return sections.joinToString(separator = System.lineSeparator() + System.lineSeparator(), postfix = System.lineSeparator())
}

private fun tomlSection(
    name: String,
    vararg entries: Pair<String, Any?>,
): String {
    val activeEntries = entries.filter { (_, value) -> value != null }
    if (activeEntries.isEmpty()) return ""
    return buildString {
        appendLine("[$name]")
        activeEntries.forEach { (key, value) ->
            appendLine("$key = ${tomlValue(checkNotNull(value))}")
        }
    }.trimEnd()
}

private fun <T> T?.changedFrom(defaultValue: T): T? = takeIf { it != defaultValue }

private fun tomlValue(value: Any): String = when (value) {
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> error("Unsupported TOML value type: ${value::class.java.name}")
}
