package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import io.github.amichne.kast.api.contract.ServerLimits
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

data class KastConfig(
    val server: ServerConfig,
    val indexing: IndexingConfig,
    val cache: CacheConfig,
    val watcher: WatcherConfig,
    val gradle: GradleConfig,
    val telemetry: TelemetryConfig,
    val profiling: ProfilingConfig,
    val backends: BackendsConfig,
    val paths: PathsConfig,
    val cli: CliConfig,
) {
    fun toServerLimits(): ServerLimits = ServerLimits(
        maxResults = server.maxResults.value,
        requestTimeoutMillis = server.requestTimeoutMillis.value,
        maxConcurrentRequests = server.maxConcurrentRequests.value,
    )

    /**
     * Applies typed runtime overrides after the base configuration has already
     * been resolved by the owning boundary.
     */
    fun withOverrides(overrides: KastConfigOverride): KastConfig = merge(overrides)

    companion object {
        fun defaults(): KastConfig {
            val paths = PathsConfig(
                installRoot = PathsInstallRoot(defaultConfigInstallRoot().toString()),
                binDir = PathsBinDir(defaultConfigBinDir().toString()),
                libDir = PathsLibDir(defaultConfigLibDir().toString()),
                cacheDir = PathsCacheDir(defaultConfigCacheDir().toString()),
                logsDir = PathsLogsDir(defaultConfigLogsDir().toString()),
                descriptorDir = PathsDescriptorDir(defaultConfigDescriptorDir().toString()),
                socketDir = PathsSocketDir(defaultConfigSocketDir()),
            )
            return KastConfig(
                server = ServerConfig(
                    maxResults = ServerMaxResults(500),
                    requestTimeoutMillis = ServerRequestTimeoutMillis(30_000L),
                    maxConcurrentRequests = ServerMaxConcurrentRequests(4),
                ),
                indexing = IndexingConfig(
                    phase2Enabled = IndexingPhase2Enabled(true),
                    phase2BatchSize = IndexingPhase2BatchSize(50),
                    phase2Parallelism = IndexingPhase2Parallelism(4),
                    phase2PriorityDepth = IndexingPhase2PriorityDepth(2),
                    identifierIndexWaitMillis = IndexingIdentifierIndexWaitMillis(10_000L),
                    referenceBatchSize = IndexingReferenceBatchSize(50),
                    remote = RemoteIndexConfig(
                        enabled = IndexingRemoteEnabled(false),
                        sourceIndexUrl = IndexingRemoteSourceIndexUrl(OptionalConfigString.Unset),
                    ),
                ),
                cache = CacheConfig(
                    enabled = CacheEnabled(true),
                    writeDelayMillis = CacheWriteDelayMillis(5_000L),
                    sourceIndexSaveDelayMillis = CacheSourceIndexSaveDelayMillis(5_000L),
                ),
                watcher = WatcherConfig(debounceMillis = WatcherDebounceMillis(200L)),
                gradle = GradleConfig(
                    toolingApiTimeoutMillis = GradleToolingApiTimeoutMillis(120_000L),
                ),
                telemetry = TelemetryConfig(
                    enabled = TelemetryEnabled(false),
                    scopes = TelemetryScopes("all"),
                    detail = TelemetryDetail("basic"),
                    outputFile = TelemetryOutputFile(OptionalConfigString.Unset),
                ),
                profiling = ProfilingConfig(
                    enabled = ProfilingEnabled(false),
                    modes = ProfilingModes("cpu"),
                    durationSeconds = ProfilingDurationSeconds(30L),
                    outputDir = ProfilingOutputDir("{logsDir}/profiling"),
                    otlpEndpoint = ProfilingOtlpEndpoint(OptionalConfigString.Unset),
                    emitManifest = ProfilingEmitManifest(true),
                ),
                backends = BackendsConfig(
                    headless = HeadlessBackendConfig(
                        enabled = HeadlessBackendEnabled(true),
                        runtimeLibsDir = HeadlessRuntimeLibsDir(
                            OptionalConfigString(defaultConfigHeadlessRuntimeLibsDir(paths.libDir.value).toString()),
                        ),
                        ideaHome = HeadlessIdeaHome(OptionalConfigString.Unset),
                    ),
                    idea = IdeaBackendConfig(enabled = IdeaBackendEnabled(true)),
                ),
                paths = paths,
                cli = CliConfig(binaryPath = CliBinaryPath(defaultConfigCliBinaryPath(paths.binDir.value).toString())),
            )
        }

        fun load(
            workspaceRoot: Path,
            configHome: () -> Path = { kastConfigHome() },
            workspaceDirectoryResolver: WorkspaceDirectoryResolver = WorkspaceDirectoryResolver(configHome = configHome),
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            val configFiles = listOf(
                workspaceDirectoryResolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml"),
                configHome().resolve("config.toml"),
            ).filter(Files::isRegularFile)
            val loaded = loadConfigOverrides(configFiles)
            return defaults().merge(loaded).merge(overrides)
        }

        /**
         * Loads the Rust CLI's fully resolved runtime configuration handoff.
         *
         * This path intentionally avoids TOML parsing in the JVM backend; the
         * Rust CLI owns config file parsing and passes this JSON to headless.
         */
        fun loadResolvedJson(
            configFile: Path,
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            val document = runtimeConfigJson.decodeFromString<RuntimeConfigDocument>(
                Files.readString(configFile),
            )
            return defaults()
                .merge(document.toKastConfigOverride())
                .merge(overrides)
        }
    }
}

private val runtimeConfigJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class RuntimeConfigDocument(
    val server: RuntimeServerConfig? = null,
    val indexing: RuntimeIndexingConfig? = null,
    val cache: RuntimeCacheConfig? = null,
    val watcher: RuntimeWatcherConfig? = null,
    val gradle: RuntimeGradleConfig? = null,
    val telemetry: RuntimeTelemetryConfig? = null,
    val profiling: RuntimeProfilingConfig? = null,
    val backends: RuntimeBackendsConfig? = null,
    val paths: RuntimePathsConfig? = null,
    val cli: RuntimeCliConfig? = null,
) {
    fun toKastConfigOverride(): KastConfigOverride = KastConfigOverride(
        server = server?.toOverride(),
        indexing = indexing?.toOverride(),
        cache = cache?.toOverride(),
        watcher = watcher?.toOverride(),
        gradle = gradle?.toOverride(),
        telemetry = telemetry?.toOverride(),
        profiling = profiling?.toOverride(),
        backends = backends?.toOverride(),
        paths = paths?.toOverride(),
        cli = cli?.toOverride(),
    )
}

@Serializable
private data class RuntimeServerConfig(
    val maxResults: Int? = null,
    val requestTimeoutMillis: Long? = null,
    val maxConcurrentRequests: Int? = null,
) {
    fun toOverride(): ServerConfigOverride = ServerConfigOverride(
        maxResults = maxResults?.let(::ServerMaxResults),
        requestTimeoutMillis = requestTimeoutMillis?.let(::ServerRequestTimeoutMillis),
        maxConcurrentRequests = maxConcurrentRequests?.let(::ServerMaxConcurrentRequests),
    )
}

@Serializable
private data class RuntimeIndexingConfig(
    val phase2Enabled: Boolean? = null,
    val phase2BatchSize: Int? = null,
    val phase2Parallelism: Int? = null,
    val phase2PriorityDepth: Int? = null,
    val identifierIndexWaitMillis: Long? = null,
    val referenceBatchSize: Int? = null,
    val remote: RuntimeRemoteIndexConfig? = null,
) {
    fun toOverride(): IndexingConfigOverride = IndexingConfigOverride(
        phase2Enabled = phase2Enabled?.let(::IndexingPhase2Enabled),
        phase2BatchSize = phase2BatchSize?.let(::IndexingPhase2BatchSize),
        phase2Parallelism = phase2Parallelism?.let(::IndexingPhase2Parallelism),
        phase2PriorityDepth = phase2PriorityDepth?.let(::IndexingPhase2PriorityDepth),
        identifierIndexWaitMillis = identifierIndexWaitMillis?.let(::IndexingIdentifierIndexWaitMillis),
        referenceBatchSize = referenceBatchSize?.let(::IndexingReferenceBatchSize),
        remote = remote?.toOverride(),
    )
}

@Serializable
private data class RuntimeRemoteIndexConfig(
    val enabled: Boolean? = null,
    val sourceIndexUrl: String? = null,
) {
    fun toOverride(): RemoteIndexConfigOverride = RemoteIndexConfigOverride(
        enabled = enabled?.let(::IndexingRemoteEnabled),
        sourceIndexUrl = sourceIndexUrl?.let(::OptionalConfigString)?.let(::IndexingRemoteSourceIndexUrl),
    )
}

@Serializable
private data class RuntimeCacheConfig(
    val enabled: Boolean? = null,
    val writeDelayMillis: Long? = null,
    val sourceIndexSaveDelayMillis: Long? = null,
) {
    fun toOverride(): CacheConfigOverride = CacheConfigOverride(
        enabled = enabled?.let(::CacheEnabled),
        writeDelayMillis = writeDelayMillis?.let(::CacheWriteDelayMillis),
        sourceIndexSaveDelayMillis = sourceIndexSaveDelayMillis?.let(::CacheSourceIndexSaveDelayMillis),
    )
}

@Serializable
private data class RuntimeWatcherConfig(
    val debounceMillis: Long? = null,
) {
    fun toOverride(): WatcherConfigOverride = WatcherConfigOverride(
        debounceMillis = debounceMillis?.let(::WatcherDebounceMillis),
    )
}

@Serializable
private data class RuntimeGradleConfig(
    val toolingApiTimeoutMillis: Long? = null,
) {
    fun toOverride(): GradleConfigOverride = GradleConfigOverride(
        toolingApiTimeoutMillis = toolingApiTimeoutMillis?.let(::GradleToolingApiTimeoutMillis),
    )
}

@Serializable
private data class RuntimeTelemetryConfig(
    val enabled: Boolean? = null,
    val scopes: String? = null,
    val detail: String? = null,
    val outputFile: String? = null,
) {
    fun toOverride(): TelemetryConfigOverride = TelemetryConfigOverride(
        enabled = enabled?.let(::TelemetryEnabled),
        scopes = scopes?.let(::TelemetryScopes),
        detail = detail?.let(::TelemetryDetail),
        outputFile = outputFile?.let(::OptionalConfigString)?.let(::TelemetryOutputFile),
    )
}

@Serializable
private data class RuntimeProfilingConfig(
    val enabled: Boolean? = null,
    val modes: String? = null,
    val durationSeconds: Long? = null,
    val outputDir: String? = null,
    val otlpEndpoint: String? = null,
    val emitManifest: Boolean? = null,
) {
    fun toOverride(): ProfilingConfigOverride = ProfilingConfigOverride(
        enabled = enabled?.let(::ProfilingEnabled),
        modes = modes?.let(::ProfilingModes),
        durationSeconds = durationSeconds?.let(::ProfilingDurationSeconds),
        outputDir = outputDir?.let(::ProfilingOutputDir),
        otlpEndpoint = otlpEndpoint?.let(::OptionalConfigString)?.let(::ProfilingOtlpEndpoint),
        emitManifest = emitManifest?.let(::ProfilingEmitManifest),
    )
}

@Serializable
private data class RuntimeBackendsConfig(
    val headless: RuntimeHeadlessBackendConfig? = null,
    val idea: RuntimeIdeaBackendConfig? = null,
) {
    fun toOverride(): BackendsConfigOverride = BackendsConfigOverride(
        headless = headless?.toOverride(),
        idea = idea?.toOverride(),
    )
}

@Serializable
private data class RuntimeHeadlessBackendConfig(
    val enabled: Boolean? = null,
    val runtimeLibsDir: String? = null,
    val ideaHome: String? = null,
) {
    fun toOverride(): HeadlessBackendConfigOverride = HeadlessBackendConfigOverride(
        enabled = enabled?.let(::HeadlessBackendEnabled),
        runtimeLibsDir = runtimeLibsDir?.let(::OptionalConfigString)?.let(::HeadlessRuntimeLibsDir),
        ideaHome = ideaHome?.let(::OptionalConfigString)?.let(::HeadlessIdeaHome),
    )
}

@Serializable
private data class RuntimeIdeaBackendConfig(
    val enabled: Boolean? = null,
) {
    fun toOverride(): IdeaBackendConfigOverride = IdeaBackendConfigOverride(
        enabled = enabled?.let(::IdeaBackendEnabled),
    )
}

@Serializable
private data class RuntimePathsConfig(
    val installRoot: String? = null,
    val binDir: String? = null,
    val libDir: String? = null,
    val cacheDir: String? = null,
    val logsDir: String? = null,
    val descriptorDir: String? = null,
    val socketDir: String? = null,
) {
    fun toOverride(): PathsConfigOverride = PathsConfigOverride(
        installRoot = installRoot?.let(::PathsInstallRoot),
        binDir = binDir?.let(::PathsBinDir),
        libDir = libDir?.let(::PathsLibDir),
        cacheDir = cacheDir?.let(::PathsCacheDir),
        logsDir = logsDir?.let(::PathsLogsDir),
        descriptorDir = descriptorDir?.let(::PathsDescriptorDir),
        socketDir = socketDir?.let(::PathsSocketDir),
    )
}

@Serializable
private data class RuntimeCliConfig(
    val binaryPath: String? = null,
) {
    fun toOverride(): CliConfigOverride = CliConfigOverride(
        binaryPath = binaryPath?.let(::CliBinaryPath),
    )
}

private fun loadConfigOverrides(configFiles: List<Path>): KastConfigOverride {
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
    indexing = indexingOverride(),
    cache = cacheOverride(),
    watcher = watcherOverride(),
    gradle = gradleOverride(),
    telemetry = telemetryOverride(),
    profiling = profilingOverride(),
    backends = backendsOverride(),
    paths = pathsOverride(),
    cli = cliOverride(),
)

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
    val runtimeLibsDir = optionalStringValue("backends.headless.runtimelibsdir")?.let(::HeadlessRuntimeLibsDir)
    val ideaHome = optionalStringValue("backends.headless.ideahome")?.let(::HeadlessIdeaHome)
    return takeIfAny(enabled, runtimeLibsDir, ideaHome) { HeadlessBackendConfigOverride(enabled, runtimeLibsDir, ideaHome) }
}

private fun Map<String, String>.ideaBackendOverride(): IdeaBackendConfigOverride? {
    val enabled = booleanValue("backends.idea.enabled")?.let(::IdeaBackendEnabled)
    return takeIfAny(enabled) { IdeaBackendConfigOverride(enabled) }
}

private fun Map<String, String>.pathsOverride(): PathsConfigOverride? {
    val installRoot = stringValue("paths.installroot")?.let(::PathsInstallRoot)
    val binDir = stringValue("paths.bindir")?.let(::PathsBinDir)
    val libDir = stringValue("paths.libdir")?.let(::PathsLibDir)
    val cacheDir = stringValue("paths.cachedir")?.let(::PathsCacheDir)
    val logsDir = stringValue("paths.logsdir")?.let(::PathsLogsDir)
    val descriptorDir = stringValue("paths.descriptordir")?.let(::PathsDescriptorDir)
    val socketDir = stringValue("paths.socketdir")?.let(::PathsSocketDir)
    return takeIfAny(installRoot, binDir, libDir, cacheDir, logsDir, descriptorDir, socketDir) {
        PathsConfigOverride(installRoot, binDir, libDir, cacheDir, logsDir, descriptorDir, socketDir)
    }
}

private fun Map<String, String>.cliOverride(): CliConfigOverride? {
    val binaryPath = stringValue("cli.binarypath")?.let(::CliBinaryPath)
    return takeIfAny(binaryPath) { CliConfigOverride(binaryPath) }
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

private inline fun <T> takeIfAny(vararg values: Any?, build: () -> T): T? =
    if (values.any { it != null }) build() else null

data class ServerConfig(
    val maxResults: ServerMaxResults,
    val requestTimeoutMillis: ServerRequestTimeoutMillis,
    val maxConcurrentRequests: ServerMaxConcurrentRequests,
)

data class IndexingConfig(
    val phase2Enabled: IndexingPhase2Enabled,
    val phase2BatchSize: IndexingPhase2BatchSize,
    val phase2Parallelism: IndexingPhase2Parallelism,
    val phase2PriorityDepth: IndexingPhase2PriorityDepth,
    val identifierIndexWaitMillis: IndexingIdentifierIndexWaitMillis,
    val referenceBatchSize: IndexingReferenceBatchSize,
    val remote: RemoteIndexConfig,
)

data class RemoteIndexConfig(
    val enabled: IndexingRemoteEnabled,
    val sourceIndexUrl: IndexingRemoteSourceIndexUrl,
)

data class CacheConfig(
    val enabled: CacheEnabled,
    val writeDelayMillis: CacheWriteDelayMillis,
    val sourceIndexSaveDelayMillis: CacheSourceIndexSaveDelayMillis,
)

data class WatcherConfig(
    val debounceMillis: WatcherDebounceMillis,
)

data class GradleConfig(
    val toolingApiTimeoutMillis: GradleToolingApiTimeoutMillis,
)

data class TelemetryConfig(
    val enabled: TelemetryEnabled,
    val scopes: TelemetryScopes,
    val detail: TelemetryDetail,
    val outputFile: TelemetryOutputFile,
)

data class ProfilingConfig(
    val enabled: ProfilingEnabled,
    val modes: ProfilingModes,
    val durationSeconds: ProfilingDurationSeconds,
    val outputDir: ProfilingOutputDir,
    val otlpEndpoint: ProfilingOtlpEndpoint,
    val emitManifest: ProfilingEmitManifest,
)

data class BackendsConfig(
    val headless: HeadlessBackendConfig,
    val idea: IdeaBackendConfig,
)

data class HeadlessBackendConfig(
    val enabled: HeadlessBackendEnabled,
    val runtimeLibsDir: HeadlessRuntimeLibsDir,
    val ideaHome: HeadlessIdeaHome,
)

data class IdeaBackendConfig(
    val enabled: IdeaBackendEnabled,
)

data class PathsConfig(
    val installRoot: PathsInstallRoot,
    val binDir: PathsBinDir,
    val libDir: PathsLibDir,
    val cacheDir: PathsCacheDir,
    val logsDir: PathsLogsDir,
    val descriptorDir: PathsDescriptorDir,
    val socketDir: PathsSocketDir,
)

data class CliConfig(
    val binaryPath: CliBinaryPath,
)

data class KastConfigOverride(
    val server: ServerConfigOverride? = null,
    val indexing: IndexingConfigOverride? = null,
    val cache: CacheConfigOverride? = null,
    val watcher: WatcherConfigOverride? = null,
    val gradle: GradleConfigOverride? = null,
    val telemetry: TelemetryConfigOverride? = null,
    val profiling: ProfilingConfigOverride? = null,
    val backends: BackendsConfigOverride? = null,
    val paths: PathsConfigOverride? = null,
    val cli: CliConfigOverride? = null,
)

data class ServerConfigOverride(
    val maxResults: ServerMaxResults? = null,
    val requestTimeoutMillis: ServerRequestTimeoutMillis? = null,
    val maxConcurrentRequests: ServerMaxConcurrentRequests? = null,
)

data class IndexingConfigOverride(
    val phase2Enabled: IndexingPhase2Enabled? = null,
    val phase2BatchSize: IndexingPhase2BatchSize? = null,
    val phase2Parallelism: IndexingPhase2Parallelism? = null,
    val phase2PriorityDepth: IndexingPhase2PriorityDepth? = null,
    val identifierIndexWaitMillis: IndexingIdentifierIndexWaitMillis? = null,
    val referenceBatchSize: IndexingReferenceBatchSize? = null,
    val remote: RemoteIndexConfigOverride? = null,
)

data class RemoteIndexConfigOverride(
    val enabled: IndexingRemoteEnabled? = null,
    val sourceIndexUrl: IndexingRemoteSourceIndexUrl? = null,
)

data class CacheConfigOverride(
    val enabled: CacheEnabled? = null,
    val writeDelayMillis: CacheWriteDelayMillis? = null,
    val sourceIndexSaveDelayMillis: CacheSourceIndexSaveDelayMillis? = null,
)

data class WatcherConfigOverride(
    val debounceMillis: WatcherDebounceMillis? = null,
)

data class GradleConfigOverride(
    val toolingApiTimeoutMillis: GradleToolingApiTimeoutMillis? = null,
)

data class TelemetryConfigOverride(
    val enabled: TelemetryEnabled? = null,
    val scopes: TelemetryScopes? = null,
    val detail: TelemetryDetail? = null,
    val outputFile: TelemetryOutputFile? = null,
)

data class ProfilingConfigOverride(
    val enabled: ProfilingEnabled? = null,
    val modes: ProfilingModes? = null,
    val durationSeconds: ProfilingDurationSeconds? = null,
    val outputDir: ProfilingOutputDir? = null,
    val otlpEndpoint: ProfilingOtlpEndpoint? = null,
    val emitManifest: ProfilingEmitManifest? = null,
)

data class BackendsConfigOverride(
    val headless: HeadlessBackendConfigOverride? = null,
    val idea: IdeaBackendConfigOverride? = null,
)

data class HeadlessBackendConfigOverride(
    val enabled: HeadlessBackendEnabled? = null,
    val runtimeLibsDir: HeadlessRuntimeLibsDir? = null,
    val ideaHome: HeadlessIdeaHome? = null,
)

data class IdeaBackendConfigOverride(
    val enabled: IdeaBackendEnabled? = null,
)

data class PathsConfigOverride(
    val installRoot: PathsInstallRoot? = null,
    val binDir: PathsBinDir? = null,
    val libDir: PathsLibDir? = null,
    val cacheDir: PathsCacheDir? = null,
    val logsDir: PathsLogsDir? = null,
    val descriptorDir: PathsDescriptorDir? = null,
    val socketDir: PathsSocketDir? = null,
)

data class CliConfigOverride(
    val binaryPath: CliBinaryPath? = null,
)

private fun KastConfig.merge(override: KastConfigOverride): KastConfig {
    val mergedPaths = paths.merge(override.paths)
    return copy(
        server = server.merge(override.server),
        indexing = indexing.merge(override.indexing),
        cache = cache.merge(override.cache),
        watcher = watcher.merge(override.watcher),
        gradle = gradle.merge(override.gradle),
        telemetry = telemetry.merge(override.telemetry),
        profiling = profiling.merge(override.profiling),
        backends = backends.merge(override.backends, mergedPaths),
        paths = mergedPaths,
        cli = cli.merge(override.cli, mergedPaths),
    )
}

private fun ServerConfig.merge(override: ServerConfigOverride?): ServerConfig = copy(
    maxResults = override?.maxResults ?: maxResults,
    requestTimeoutMillis = override?.requestTimeoutMillis ?: requestTimeoutMillis,
    maxConcurrentRequests = override?.maxConcurrentRequests ?: maxConcurrentRequests,
)

private fun IndexingConfig.merge(override: IndexingConfigOverride?): IndexingConfig = copy(
    phase2Enabled = override?.phase2Enabled ?: phase2Enabled,
    phase2BatchSize = override?.phase2BatchSize ?: phase2BatchSize,
    phase2Parallelism = override?.phase2Parallelism ?: phase2Parallelism,
    phase2PriorityDepth = override?.phase2PriorityDepth ?: phase2PriorityDepth,
    identifierIndexWaitMillis = override?.identifierIndexWaitMillis ?: identifierIndexWaitMillis,
    referenceBatchSize = override?.referenceBatchSize ?: referenceBatchSize,
    remote = remote.merge(override?.remote),
)

private fun RemoteIndexConfig.merge(override: RemoteIndexConfigOverride?): RemoteIndexConfig = copy(
    enabled = override?.enabled ?: enabled,
    sourceIndexUrl = override?.sourceIndexUrl ?: sourceIndexUrl,
)

private fun CacheConfig.merge(override: CacheConfigOverride?): CacheConfig = copy(
    enabled = override?.enabled ?: enabled,
    writeDelayMillis = override?.writeDelayMillis ?: writeDelayMillis,
    sourceIndexSaveDelayMillis = override?.sourceIndexSaveDelayMillis ?: sourceIndexSaveDelayMillis,
)

private fun WatcherConfig.merge(override: WatcherConfigOverride?): WatcherConfig = copy(
    debounceMillis = override?.debounceMillis ?: debounceMillis,
)

private fun GradleConfig.merge(override: GradleConfigOverride?): GradleConfig = copy(
    toolingApiTimeoutMillis = override?.toolingApiTimeoutMillis ?: toolingApiTimeoutMillis,
)

private fun TelemetryConfig.merge(override: TelemetryConfigOverride?): TelemetryConfig = copy(
    enabled = override?.enabled ?: enabled,
    scopes = override?.scopes ?: scopes,
    detail = override?.detail ?: detail,
    outputFile = override?.outputFile ?: outputFile,
)

private fun ProfilingConfig.merge(override: ProfilingConfigOverride?): ProfilingConfig = copy(
    enabled = override?.enabled ?: enabled,
    modes = override?.modes ?: modes,
    durationSeconds = override?.durationSeconds ?: durationSeconds,
    outputDir = override?.outputDir ?: outputDir,
    otlpEndpoint = override?.otlpEndpoint ?: otlpEndpoint,
    emitManifest = override?.emitManifest ?: emitManifest,
)

private fun BackendsConfig.merge(
    override: BackendsConfigOverride?,
    paths: PathsConfig,
): BackendsConfig = copy(
    headless = headless.merge(override?.headless, paths),
    idea = idea.merge(override?.idea),
)

private fun HeadlessBackendConfig.merge(
    override: HeadlessBackendConfigOverride?,
    paths: PathsConfig,
): HeadlessBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
    runtimeLibsDir = override?.runtimeLibsDir ?: runtimeLibsDir,
    ideaHome = override?.ideaHome ?: ideaHome,
)

private fun IdeaBackendConfig.merge(override: IdeaBackendConfigOverride?): IdeaBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
)

private fun PathsConfig.merge(override: PathsConfigOverride?): PathsConfig {
    val mergedInstallRoot = override?.installRoot ?: installRoot
    val mergedBinDir = override?.binDir ?: PathsBinDir(defaultConfigBinDir(mergedInstallRoot.value).toString())
    val mergedLibDir = override?.libDir ?: PathsLibDir(defaultConfigLibDir(mergedInstallRoot.value).toString())
    val mergedCacheDir = override?.cacheDir ?: PathsCacheDir(defaultConfigCacheDir(mergedInstallRoot.value).toString())
    return copy(
        installRoot = mergedInstallRoot,
        binDir = mergedBinDir,
        libDir = mergedLibDir,
        cacheDir = mergedCacheDir,
        logsDir = override?.logsDir ?: PathsLogsDir(defaultConfigLogsDir(mergedInstallRoot.value).toString()),
        descriptorDir = override?.descriptorDir
                        ?: PathsDescriptorDir(defaultConfigDescriptorDir(mergedCacheDir.value).toString()),
        socketDir = override?.socketDir ?: socketDir,
    )
}

private fun CliConfig.merge(
    override: CliConfigOverride?,
    paths: PathsConfig,
): CliConfig = copy(
    binaryPath = override?.binaryPath ?: CliBinaryPath(defaultConfigCliBinaryPath(paths.binDir.value).toString()),
)
