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
        .mergeResolved(document.toResolvedRuntimeConfigOverride())
}

private val runtimeConfigJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class RuntimeConfigDocument(
    val server: RuntimeServerConfig? = null,
    val runtime: RuntimeRuntimeConfig? = null,
    val projectOpen: RuntimeProjectOpenConfig? = null,
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
        runtime = runtime?.toOverride(),
        projectOpen = projectOpen?.toOverride(),
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

    fun toResolvedRuntimeConfigOverride(): ResolvedRuntimeConfigOverride = ResolvedRuntimeConfigOverride(
        backends = backends?.toResolvedOverride(),
    )
}

private data class ResolvedRuntimeConfigOverride(
    val backends: ResolvedBackendsConfigOverride? = null,
)

private data class ResolvedBackendsConfigOverride(
    val headless: ResolvedHeadlessBackendConfigOverride? = null,
)

private data class ResolvedHeadlessBackendConfigOverride(
    val runtimeLibsDir: HeadlessRuntimeLibsDir? = null,
    val ideaHome: HeadlessIdeaHome? = null,
)

private fun KastConfig.mergeResolved(override: ResolvedRuntimeConfigOverride): KastConfig = copy(
    backends = backends.mergeResolved(override.backends),
)

private fun BackendsConfig.mergeResolved(
    override: ResolvedBackendsConfigOverride?,
): BackendsConfig = copy(
    headless = headless.mergeResolved(override?.headless),
)

private fun HeadlessBackendConfig.mergeResolved(
    override: ResolvedHeadlessBackendConfigOverride?,
): HeadlessBackendConfig = copy(
    runtimeLibsDir = override?.runtimeLibsDir ?: runtimeLibsDir,
    ideaHome = override?.ideaHome ?: ideaHome,
)

@Serializable
private data class RuntimeRuntimeConfig(
    val defaultBackend: String? = null,
    val strictPluginMatching: Boolean? = null,
    val ideaLaunch: RuntimeIdeaLaunchConfig? = null,
) {
    fun toOverride(): RuntimeConfigOverride = RuntimeConfigOverride(
        defaultBackend = defaultBackend?.let(::RuntimeDefaultBackend),
        strictPluginMatching = strictPluginMatching?.let(::RuntimeStrictPluginMatching),
        ideaLaunch = ideaLaunch?.toOverride(),
    )
}

@Serializable
private data class RuntimeIdeaLaunchConfig(
    val enabled: Boolean? = null,
    val command: String? = null,
    val waitTimeoutMillis: Long? = null,
) {
    fun toOverride(): IdeaLaunchConfigOverride = IdeaLaunchConfigOverride(
        enabled = enabled?.let(::IdeaLaunchEnabled),
        command = command?.let(::IdeaLaunchCommand),
        waitTimeoutMillis = waitTimeoutMillis?.let(::IdeaLaunchWaitTimeoutMillis),
    )
}

@Serializable
private data class RuntimeProjectOpenConfig(
    val profileAutoInit: Boolean? = null,
    val profile: String? = null,
    val autoExcludeGit: Boolean? = null,
    val gradleLoadEnabled: Boolean? = null,
) {
    fun toOverride(): ProjectOpenConfigOverride = ProjectOpenConfigOverride(
        profileAutoInit = profileAutoInit?.let(::ProjectOpenProfileAutoInit),
        profile = profile?.let(::ProjectOpenProfile),
        autoExcludeGit = autoExcludeGit?.let(::ProjectOpenAutoExcludeGit),
        gradleLoadEnabled = gradleLoadEnabled?.let(::ProjectOpenGradleLoadEnabled),
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
    val criticalPaths: List<String>? = null,
    val ignoredPaths: List<String>? = null,
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
    val batchSize: Int? = null,
) {
    fun toOverride(): GraphIndexingConfigOverride = GraphIndexingConfigOverride(
        batchSize = batchSize?.let(::GraphIndexingBatchSize),
    )
}

@Serializable
private data class RuntimeRelationshipIndexingConfig(
    val enabled: Boolean? = null,
    val batchSize: Int? = null,
    val parallelism: Int? = null,
    val modulePriorityDepth: Int? = null,
) {
    fun toOverride(): RelationshipIndexingConfigOverride = RelationshipIndexingConfigOverride(
        enabled = enabled?.let(::RelationshipIndexingEnabled),
        batchSize = batchSize?.let(::RelationshipIndexingBatchSize),
        parallelism = parallelism?.let(::RelationshipIndexingParallelism),
        modulePriorityDepth = modulePriorityDepth?.let(::RelationshipIndexingModulePriorityDepth),
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

    fun toResolvedOverride(): ResolvedBackendsConfigOverride? {
        val resolvedHeadless = headless?.toResolvedOverride()
        return takeIfAny(resolvedHeadless) { ResolvedBackendsConfigOverride(resolvedHeadless) }
    }
}

@Serializable
private data class RuntimeHeadlessBackendConfig(
    val enabled: Boolean? = null,
    val runtimeLibsDir: String? = null,
    val ideaHome: String? = null,
) {
    fun toOverride(): HeadlessBackendConfigOverride = HeadlessBackendConfigOverride(
        enabled = enabled?.let(::HeadlessBackendEnabled),
    )

    fun toResolvedOverride(): ResolvedHeadlessBackendConfigOverride? {
        val resolvedRuntimeLibsDir = runtimeLibsDir?.let(::OptionalConfigString)?.let(::HeadlessRuntimeLibsDir)
        val resolvedIdeaHome = ideaHome?.let(::OptionalConfigString)?.let(::HeadlessIdeaHome)
        return takeIfAny(resolvedRuntimeLibsDir, resolvedIdeaHome) {
            ResolvedHeadlessBackendConfigOverride(
                runtimeLibsDir = resolvedRuntimeLibsDir,
                ideaHome = resolvedIdeaHome,
            )
        }
    }
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
