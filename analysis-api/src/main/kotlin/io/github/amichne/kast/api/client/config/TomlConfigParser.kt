package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import java.nio.file.Files
import java.nio.file.Path

internal fun loadConfigOverrides(configFiles: List<Path>): KastConfigOverride {
    val values = linkedMapOf<String, String>()
    configFiles.asReversed().forEach { configFile ->
        values.putAll(parseConfigValues(configFile))
    }
    return values.toKastConfigOverride()
}

private fun parseConfigValues(configFile: Path): Map<String, String> {
    val values = linkedMapOf<String, String>()
    var section = ""
    Files.readAllLines(configFile).forEachIndexed { index, rawLine ->
        val line = rawLine.withoutTomlComment().trim()
        if (line.isBlank()) return@forEachIndexed
        if (line.startsWith("[") && line.endsWith("]")) {
            section = normalizeConfigPath(line.removePrefix("[").removeSuffix("]"))
            return@forEachIndexed
        }

        val separator = line.indexOf('=')
        require(separator > 0) { "Invalid Kast config line ${index + 1} in $configFile: $rawLine" }
        val key = normalizeConfigPath(
            listOf(section, line.substring(0, separator).trim())
                .filter(String::isNotBlank)
                .joinToString("."),
        )
        values[key] = line.substring(separator + 1).trim().parseTomlScalar()
    }
    return values
}

private fun String.withoutTomlComment(): String {
    var quoted = false
    var quote = '\u0000'
    var escaped = false
    forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            quoted && char == quote -> quoted = false
            !quoted && (char == '"' || char == '\'') -> {
                quoted = true
                quote = char
            }
            !quoted && char == '#' -> return substring(0, index)
        }
    }
    return this
}

private fun String.parseTomlScalar(): String {
    val trimmed = trim().removeSuffix(",").trim()
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return trimmed.substring(1, trimmed.lastIndex)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }
    if (trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'') {
        return trimmed.substring(1, trimmed.lastIndex)
    }
    return trimmed
}

private fun normalizeConfigPath(path: String): String =
    path.split('.')
        .joinToString(".") { segment -> segment.filterNot { it == '-' || it == '_' }.lowercase() }

private fun Map<String, String>.toKastConfigOverride(): KastConfigOverride = KastConfigOverride(
    server = serverOverride(),
    runtime = runtimeOverride(),
    projectOpen = projectOpenOverride(),
    codex = codexOverride(),
    indexing = indexingOverride(),
    cache = cacheOverride(),
    watcher = watcherOverride(),
    gradle = gradleOverride(),
    telemetry = telemetryOverride(),
    profiling = profilingOverride(),
    backends = backendsOverride(),
)

private fun Map<String, String>.codexOverride(): CodexConfigOverride? {
    val enabled = booleanValue("codex.hooks.enabled")?.let(::CodexHooksEnabled)
    val sessionStart = booleanValue("codex.hooks.sessionstart")?.let(::CodexSessionStartEnabled)
    val postToolUse = booleanValue("codex.hooks.posttooluse")?.let(::CodexPostToolUseEnabled)
    val hooks = takeIfAny(enabled, sessionStart, postToolUse) {
        CodexHooksConfigOverride(enabled, sessionStart, postToolUse)
    }
    return hooks?.let(::CodexConfigOverride)
}

private fun Map<String, String>.runtimeOverride(): RuntimeConfigOverride? {
    val defaultBackend = stringValue("runtime.defaultbackend")?.let(::RuntimeDefaultBackend)
    val strictPluginMatching = booleanValue("runtime.strictpluginmatching")?.let(::RuntimeStrictPluginMatching)
    val ideaLaunch = ideaLaunchOverride()
    return takeIfAny(defaultBackend, strictPluginMatching, ideaLaunch) {
        RuntimeConfigOverride(defaultBackend, strictPluginMatching, ideaLaunch)
    }
}

private fun Map<String, String>.ideaLaunchOverride(): IdeaLaunchConfigOverride? {
    val enabled = booleanValue("runtime.idealaunch.enabled")?.let(::IdeaLaunchEnabled)
    val command = stringValue("runtime.idealaunch.command")?.let(::IdeaLaunchCommand)
    val waitTimeoutMillis = longValue("runtime.idealaunch.waittimeoutmillis")?.let(::IdeaLaunchWaitTimeoutMillis)
    return takeIfAny(enabled, command, waitTimeoutMillis) {
        IdeaLaunchConfigOverride(enabled, command, waitTimeoutMillis)
    }
}

private fun Map<String, String>.projectOpenOverride(): ProjectOpenConfigOverride? {
    val profileAutoInit = booleanValue("projectopen.profileautoinit")?.let(::ProjectOpenProfileAutoInit)
    val profile = stringValue("projectopen.profile")?.let(::ProjectOpenProfile)
    val autoExcludeGit = booleanValue("projectopen.autoexcludegit")?.let(::ProjectOpenAutoExcludeGit)
    val gradleLoadEnabled = booleanValue("projectopen.gradleloadenabled")?.let(::ProjectOpenGradleLoadEnabled)
    return takeIfAny(profileAutoInit, profile, autoExcludeGit, gradleLoadEnabled) {
        ProjectOpenConfigOverride(profileAutoInit, profile, autoExcludeGit, gradleLoadEnabled)
    }
}

private fun Map<String, String>.serverOverride(): ServerConfigOverride? {
    val maxResults = intValue("server.maxresults")?.let(::ServerMaxResults)
    val requestTimeoutMillis = longValue("server.requesttimeoutmillis")?.let(::ServerRequestTimeoutMillis)
    val maxConcurrentRequests = intValue("server.maxconcurrentrequests")?.let(::ServerMaxConcurrentRequests)
    return takeIfAny(maxResults, requestTimeoutMillis, maxConcurrentRequests) {
        ServerConfigOverride(maxResults, requestTimeoutMillis, maxConcurrentRequests)
    }
}

private fun Map<String, String>.indexingOverride(): IndexingConfigOverride? {
    val phase2Enabled = booleanValue("indexing.phase2enabled")?.let(::IndexingPhase2Enabled)
    val phase2BatchSize = intValue("indexing.phase2batchsize")?.let(::IndexingPhase2BatchSize)
    val phase2Parallelism = intValue("indexing.phase2parallelism")?.let(::IndexingPhase2Parallelism)
    val phase2PriorityDepth = intValue("indexing.phase2prioritydepth")?.let(::IndexingPhase2PriorityDepth)
    val identifierIndexWaitMillis = longValue("indexing.identifierindexwaitmillis")?.let(::IndexingIdentifierIndexWaitMillis)
    val referenceBatchSize = intValue("indexing.referencebatchsize")?.let(::IndexingReferenceBatchSize)
    val remote = remoteIndexOverride()
    return takeIfAny(phase2Enabled, phase2BatchSize, phase2Parallelism, phase2PriorityDepth, identifierIndexWaitMillis, referenceBatchSize, remote) {
        IndexingConfigOverride(
            phase2Enabled = phase2Enabled,
            phase2BatchSize = phase2BatchSize,
            phase2Parallelism = phase2Parallelism,
            phase2PriorityDepth = phase2PriorityDepth,
            identifierIndexWaitMillis = identifierIndexWaitMillis,
            referenceBatchSize = referenceBatchSize,
            remote = remote,
        )
    }
}

private fun Map<String, String>.remoteIndexOverride(): RemoteIndexConfigOverride? {
    val enabled = booleanValue("indexing.remote.enabled")?.let(::IndexingRemoteEnabled)
    val sourceIndexUrl = optionalStringValue("indexing.remote.sourceindexurl")?.let(::IndexingRemoteSourceIndexUrl)
    return takeIfAny(enabled, sourceIndexUrl) { RemoteIndexConfigOverride(enabled, sourceIndexUrl) }
}

private fun Map<String, String>.cacheOverride(): CacheConfigOverride? {
    val enabled = booleanValue("cache.enabled")?.let(::CacheEnabled)
    val writeDelayMillis = longValue("cache.writedelaymillis")?.let(::CacheWriteDelayMillis)
    val sourceIndexSaveDelayMillis = longValue("cache.sourceindexsavedelaymillis")?.let(::CacheSourceIndexSaveDelayMillis)
    return takeIfAny(enabled, writeDelayMillis, sourceIndexSaveDelayMillis) {
        CacheConfigOverride(enabled, writeDelayMillis, sourceIndexSaveDelayMillis)
    }
}

private fun Map<String, String>.watcherOverride(): WatcherConfigOverride? {
    val debounceMillis = longValue("watcher.debouncemillis")?.let(::WatcherDebounceMillis)
    return takeIfAny(debounceMillis) { WatcherConfigOverride(debounceMillis) }
}

private fun Map<String, String>.gradleOverride(): GradleConfigOverride? {
    val toolingApiTimeoutMillis = longValue("gradle.toolingapitimeoutmillis")?.let(::GradleToolingApiTimeoutMillis)
    return takeIfAny(toolingApiTimeoutMillis) {
        GradleConfigOverride(toolingApiTimeoutMillis)
    }
}

private fun Map<String, String>.telemetryOverride(): TelemetryConfigOverride? {
    val enabled = booleanValue("telemetry.enabled")?.let(::TelemetryEnabled)
    val scopes = stringValue("telemetry.scopes")?.let(::TelemetryScopes)
    val detail = stringValue("telemetry.detail")?.let(::TelemetryDetail)
    val outputFile = optionalStringValue("telemetry.outputfile")?.let(::TelemetryOutputFile)
    return takeIfAny(enabled, scopes, detail, outputFile) {
        TelemetryConfigOverride(enabled, scopes, detail, outputFile)
    }
}

private fun Map<String, String>.profilingOverride(): ProfilingConfigOverride? {
    val enabled = booleanValue("profiling.enabled")?.let(::ProfilingEnabled)
    val modes = stringValue("profiling.modes")?.let(::ProfilingModes)
    val durationSeconds = longValue("profiling.durationseconds")?.let(::ProfilingDurationSeconds)
    val outputDir = stringValue("profiling.outputdir")?.let(::ProfilingOutputDir)
    val otlpEndpoint = optionalStringValue("profiling.otlpendpoint")?.let(::ProfilingOtlpEndpoint)
    val emitManifest = booleanValue("profiling.emitmanifest")?.let(::ProfilingEmitManifest)
    return takeIfAny(enabled, modes, durationSeconds, outputDir, otlpEndpoint, emitManifest) {
        ProfilingConfigOverride(enabled, modes, durationSeconds, outputDir, otlpEndpoint, emitManifest)
    }
}

private fun Map<String, String>.backendsOverride(): BackendsConfigOverride? {
    val headless = headlessBackendOverride()
    val idea = ideaBackendOverride()
    return takeIfAny(headless, idea) { BackendsConfigOverride(headless, idea) }
}

private fun Map<String, String>.headlessBackendOverride(): HeadlessBackendConfigOverride? {
    val enabled = booleanValue("backends.headless.enabled")?.let(::HeadlessBackendEnabled)
    return takeIfAny(enabled) { HeadlessBackendConfigOverride(enabled = enabled) }
}

private fun Map<String, String>.ideaBackendOverride(): IdeaBackendConfigOverride? {
    val enabled = booleanValue("backends.idea.enabled")?.let(::IdeaBackendEnabled)
    return takeIfAny(enabled) { IdeaBackendConfigOverride(enabled) }
}

private fun Map<String, String>.stringValue(key: String): String? = get(key)

private fun Map<String, String>.optionalStringValue(key: String): OptionalConfigString? = get(key)?.let(::OptionalConfigString)

private fun Map<String, String>.intValue(key: String): Int? = get(key)?.toInt()

private fun Map<String, String>.longValue(key: String): Long? = get(key)?.toLong()

private fun Map<String, String>.booleanValue(key: String): Boolean? = get(key)?.let { value ->
    when (value.lowercase()) {
        "true", "t", "1", "yes" -> true
        "false", "f", "0", "no" -> false
        else -> error("Invalid boolean value for $key: $value")
    }
}

internal inline fun <T> takeIfAny(vararg values: Any?, build: () -> T): T? =
    if (values.any { it != null }) build() else null
