package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

internal fun KastConfig.mergeResolvedJson(configFile: Path): KastConfig {
    val document = runtimeConfigJson.decodeFromString<RuntimeConfigDocument>(
        Files.readString(configFile),
    )
    return merge(document.toKastConfigOverride())
}

private val runtimeConfigJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class RuntimeConfigDocument(
    val server: RuntimeServerConfig? = null,
    val codex: RuntimeCodexConfig? = null,
    val indexing: RuntimeIndexingConfig? = null,
    val cache: RuntimeCacheConfig? = null,
    val watcher: RuntimeWatcherConfig? = null,
    val gradle: RuntimeGradleConfig? = null,
    val telemetry: RuntimeTelemetryConfig? = null,
    val profiling: RuntimeProfilingConfig? = null,
    val paths: RuntimePathsConfig? = null,
    val cli: RuntimeCliConfig? = null,
) {
    fun toKastConfigOverride(): KastConfigOverride = KastConfigOverride(
        server = server?.toOverride(),
        codex = codex?.toOverride(),
        indexing = indexing?.toOverride(),
        cache = cache?.toOverride(),
        watcher = watcher?.toOverride(),
        gradle = gradle?.toOverride(),
        telemetry = telemetry?.toOverride(),
        profiling = profiling?.toOverride(),
        paths = paths?.toOverride(),
        cli = cli?.toOverride(),
    )
}

@Serializable
private data class RuntimeCodexConfig(
    val hooks: RuntimeCodexHooksConfig? = null,
) {
    fun toOverride(): CodexConfigOverride = CodexConfigOverride(
        hooks = hooks?.toOverride(),
    )
}

@Serializable
private data class RuntimeCodexHooksConfig(
    val enabled: Boolean? = null,
    val sessionStart: Boolean? = null,
    val postToolUse: Boolean? = null,
    val autoStartIndexer: Boolean? = null,
) {
    fun toOverride(): CodexHooksConfigOverride = CodexHooksConfigOverride(
        enabled = enabled?.let(::CodexHooksEnabled),
        sessionStart = sessionStart?.let(::CodexSessionStartEnabled),
        postToolUse = postToolUse?.let(::CodexPostToolUseEnabled),
        autoStartIndexer = autoStartIndexer
            ?.let(IndexerAutoStartConsent::fromBoolean)
            ?.let(::CodexAutoStartIndexer),
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
    val criticalPaths: List<WorkspaceIndexingPattern>? = null,
    val ignoredPaths: List<WorkspaceIndexingPattern>? = null,
    val graph: RuntimeGraphIndexingConfig? = null,
    val relationships: RuntimeRelationshipIndexingConfig? = null,
    val identifierIndexWaitMillis: Long? = null,
    val remote: RuntimeRemoteIndexConfig? = null,
) {
    fun toOverride(): IndexingConfigOverride = IndexingConfigOverride(
        criticalPaths = criticalPaths?.let(::IndexingCriticalPaths),
        ignoredPaths = ignoredPaths?.let(::IndexingIgnoredPaths),
        graph = graph?.toOverride(),
        relationships = relationships?.toOverride(),
        identifierIndexWaitMillis = identifierIndexWaitMillis?.let(::IndexingIdentifierIndexWaitMillis),
        remote = remote?.toOverride(),
    )
}

@Serializable
private data class RuntimeGraphIndexingConfig(
    val batchSize: GraphIndexingBatchSize? = null,
) {
    fun toOverride(): GraphIndexingConfigOverride = GraphIndexingConfigOverride(
        batchSize = batchSize,
    )
}

@Serializable
private data class RuntimeRelationshipIndexingConfig(
    val enabled: Boolean? = null,
    val batchSize: RelationshipIndexingBatchSize? = null,
    val parallelism: RelationshipIndexingParallelism? = null,
    val modulePriorityDepth: RelationshipIndexingModulePriorityDepth? = null,
) {
    fun toOverride(): RelationshipIndexingConfigOverride = RelationshipIndexingConfigOverride(
        enabled = enabled?.let(::RelationshipIndexingEnabled),
        batchSize = batchSize,
        parallelism = parallelism,
        modulePriorityDepth = modulePriorityDepth,
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
private data class RuntimePathsConfig(
    val installRoot: String? = null,
    val binDir: String? = null,
    val libDir: String? = null,
    val cacheDir: String? = null,
    val logsDir: String? = null,
    val runtimeDir: String? = null,
    val descriptorDir: String? = null,
    val socketDir: String? = null,
) {
    fun toOverride(): PathsConfigOverride = PathsConfigOverride(
        installRoot = installRoot?.let(::PathsInstallRoot),
        binDir = binDir?.let(::PathsBinDir),
        libDir = libDir?.let(::PathsLibDir),
        cacheDir = cacheDir?.let(::PathsCacheDir),
        logsDir = logsDir?.let(::PathsLogsDir),
        runtimeDir = runtimeDir?.let(::PathsRuntimeDir),
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
