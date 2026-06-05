package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.KastConfig

internal fun KastSettingsState.toWorkspaceToml(defaults: KastConfig = KastConfig.defaults()): String {
    val sections = buildList {
        add(
            tomlSection(
                "server",
                "maxResults" to serverMaxResults.changedFrom(defaults.server.maxResults.value),
                "requestTimeoutMillis" to serverRequestTimeoutMillis.changedFrom(defaults.server.requestTimeoutMillis.value),
                "maxConcurrentRequests" to serverMaxConcurrentRequests.changedFrom(defaults.server.maxConcurrentRequests.value),
            ),
        )
        add(
            tomlSection(
                "indexing",
                "phase2Enabled" to indexingPhase2Enabled.changedFrom(defaults.indexing.phase2Enabled.value),
                "phase2BatchSize" to indexingPhase2BatchSize.changedFrom(defaults.indexing.phase2BatchSize.value),
                "phase2PriorityDepth" to indexingPhase2PriorityDepth.changedFrom(defaults.indexing.phase2PriorityDepth.value),
                "identifierIndexWaitMillis" to indexingIdentifierIndexWaitMillis.changedFrom(defaults.indexing.identifierIndexWaitMillis.value),
                "referenceBatchSize" to indexingReferenceBatchSize.changedFrom(defaults.indexing.referenceBatchSize.value),
            ),
        )
        add(
            tomlSection(
                "indexing.remote",
                "enabled" to indexingRemoteEnabled.changedFrom(defaults.indexing.remote.enabled.value),
                "sourceIndexUrl" to indexingRemoteSourceIndexUrl.changedFrom(defaults.indexing.remote.sourceIndexUrl.value.orNull),
            ),
        )
        add(
            tomlSection(
                "cache",
                "enabled" to cacheEnabled.changedFrom(defaults.cache.enabled.value),
                "writeDelayMillis" to cacheWriteDelayMillis.changedFrom(defaults.cache.writeDelayMillis.value),
                "sourceIndexSaveDelayMillis" to cacheSourceIndexSaveDelayMillis.changedFrom(defaults.cache.sourceIndexSaveDelayMillis.value),
            ),
        )
        add(tomlSection("watcher", "debounceMillis" to watcherDebounceMillis.changedFrom(defaults.watcher.debounceMillis.value)))
        add(
            tomlSection(
                "gradle",
                "toolingApiTimeoutMillis" to gradleToolingApiTimeoutMillis.changedFrom(defaults.gradle.toolingApiTimeoutMillis.value),
            ),
        )
        add(
            tomlSection(
                "telemetry",
                "enabled" to telemetryEnabled.changedFrom(defaults.telemetry.enabled.value),
                "scopes" to telemetryScopes.changedFrom(defaults.telemetry.scopes.value),
                "detail" to telemetryDetail.changedFrom(defaults.telemetry.detail.value),
                "outputFile" to telemetryOutputFile.changedFrom(defaults.telemetry.outputFile.value.orNull),
            ),
        )
        add(
            tomlSection(
                "backends.headless",
                "enabled" to backendsHeadlessEnabled.changedFrom(defaults.backends.headless.enabled.value),
                "runtimeLibsDir" to backendsHeadlessRuntimeLibsDir.changedFrom(defaults.backends.headless.runtimeLibsDir.value.orNull),
                "ideaHome" to backendsHeadlessIdeaHome.changedFrom(defaults.backends.headless.ideaHome.value.orNull),
            ),
        )
        add(tomlSection("backends.intellij", "enabled" to backendsIntellijEnabled.changedFrom(defaults.backends.intellij.enabled.value)))
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
