package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.StringDecoder
import com.sksamuel.hoplite.loc
import com.sksamuel.hoplite.toml.TomlParser
import io.github.amichne.kast.api.contract.ServerLimits
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
                    toolingApiTimeoutMillis = GradleToolingApiTimeoutMillis(60_000L),
                    maxIncludedProjects = GradleMaxIncludedProjects(200),
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
                    standalone = StandaloneBackendConfig(
                        enabled = StandaloneBackendEnabled(true),
                        runtimeLibsDir = StandaloneRuntimeLibsDir(
                            OptionalConfigString(defaultConfigStandaloneRuntimeLibsDir(paths.libDir.value).toString()),
                        ),
                    ),
                    intellij = IntellijBackendConfig(enabled = IntellijBackendEnabled(true)),
                ),
                paths = paths,
                cli = CliConfig(binaryPath = CliBinaryPath(defaultConfigCliBinaryPath(paths.binDir.value).toString())),
            )
        }

        @OptIn(ExperimentalHoplite::class)
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
            val loaded = loadConfigOverride(configFiles)
            return defaults().merge(loaded).merge(overrides)
        }

        @OptIn(ExperimentalHoplite::class)
        private fun loadConfigOverride(configFiles: List<Path>): KastConfigOverride {
            if (configFiles.isEmpty()) return KastConfigOverride()
            val root = ConfigLoaderBuilder.empty()
                .withClassLoader(KastConfig::class.java.classLoader)
                .addDecoder(StringDecoder())
                .addDefaultPreprocessors()
                .addParser("toml", TomlParser())
                .withExplicitSealedTypes()
                .allowEmptyConfigFiles()
                .build()
                .loadNodeOrThrow(configFiles.map(Path::toString))

            return (root as? MapNode)?.toKastConfigOverride() ?: KastConfigOverride()
        }
    }
}

data class ServerConfig(
    val maxResults: ServerMaxResults,
    val requestTimeoutMillis: ServerRequestTimeoutMillis,
    val maxConcurrentRequests: ServerMaxConcurrentRequests,
)

data class IndexingConfig(
    val phase2Enabled: IndexingPhase2Enabled,
    val phase2BatchSize: IndexingPhase2BatchSize,
    val phase2Parallelism: IndexingPhase2Parallelism,
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
    val maxIncludedProjects: GradleMaxIncludedProjects,
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
    val standalone: StandaloneBackendConfig,
    val intellij: IntellijBackendConfig,
)

data class StandaloneBackendConfig(
    val enabled: StandaloneBackendEnabled,
    val runtimeLibsDir: StandaloneRuntimeLibsDir,
)

data class IntellijBackendConfig(
    val enabled: IntellijBackendEnabled,
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
    val maxIncludedProjects: GradleMaxIncludedProjects? = null,
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
    val standalone: StandaloneBackendConfigOverride? = null,
    val intellij: IntellijBackendConfigOverride? = null,
)

data class StandaloneBackendConfigOverride(
    val enabled: StandaloneBackendEnabled? = null,
    val runtimeLibsDir: StandaloneRuntimeLibsDir? = null,
)

data class IntellijBackendConfigOverride(
    val enabled: IntellijBackendEnabled? = null,
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

private fun MapNode.toKastConfigOverride(): KastConfigOverride = KastConfigOverride(
    server = childMap("server")?.toServerConfigOverride(),
    indexing = childMap("indexing")?.toIndexingConfigOverride(),
    cache = childMap("cache")?.toCacheConfigOverride(),
    watcher = childMap("watcher")?.toWatcherConfigOverride(),
    gradle = childMap("gradle")?.toGradleConfigOverride(),
    telemetry = childMap("telemetry")?.toTelemetryConfigOverride(),
    profiling = childMap("profiling")?.toProfilingConfigOverride(),
    backends = childMap("backends")?.toBackendsConfigOverride(),
    paths = childMap("paths")?.toPathsConfigOverride(),
    cli = childMap("cli")?.toCliConfigOverride(),
)

private fun MapNode.toServerConfigOverride(): ServerConfigOverride = ServerConfigOverride(
    maxResults = intField("maxResults")?.let(::ServerMaxResults),
    requestTimeoutMillis = longField("requestTimeoutMillis")?.let(::ServerRequestTimeoutMillis),
    maxConcurrentRequests = intField("maxConcurrentRequests")?.let(::ServerMaxConcurrentRequests),
)

private fun MapNode.toIndexingConfigOverride(): IndexingConfigOverride = IndexingConfigOverride(
    phase2Enabled = booleanField("phase2Enabled")?.let(::IndexingPhase2Enabled),
    phase2BatchSize = intField("phase2BatchSize")?.let(::IndexingPhase2BatchSize),
    phase2Parallelism = intField("phase2Parallelism")?.let(::IndexingPhase2Parallelism),
    identifierIndexWaitMillis = longField("identifierIndexWaitMillis")?.let(::IndexingIdentifierIndexWaitMillis),
    referenceBatchSize = intField("referenceBatchSize")?.let(::IndexingReferenceBatchSize),
    remote = childMap("remote")?.toRemoteIndexConfigOverride(),
)

private fun MapNode.toRemoteIndexConfigOverride(): RemoteIndexConfigOverride = RemoteIndexConfigOverride(
    enabled = booleanField("enabled")?.let(::IndexingRemoteEnabled),
    sourceIndexUrl = optionalStringField("sourceIndexUrl")?.let(::IndexingRemoteSourceIndexUrl),
)

private fun MapNode.toCacheConfigOverride(): CacheConfigOverride = CacheConfigOverride(
    enabled = booleanField("enabled")?.let(::CacheEnabled),
    writeDelayMillis = longField("writeDelayMillis")?.let(::CacheWriteDelayMillis),
    sourceIndexSaveDelayMillis = longField("sourceIndexSaveDelayMillis")?.let(::CacheSourceIndexSaveDelayMillis),
)

private fun MapNode.toWatcherConfigOverride(): WatcherConfigOverride = WatcherConfigOverride(
    debounceMillis = longField("debounceMillis")?.let(::WatcherDebounceMillis),
)

private fun MapNode.toGradleConfigOverride(): GradleConfigOverride = GradleConfigOverride(
    toolingApiTimeoutMillis = longField("toolingApiTimeoutMillis")?.let(::GradleToolingApiTimeoutMillis),
    maxIncludedProjects = intField("maxIncludedProjects")?.let(::GradleMaxIncludedProjects),
)

private fun MapNode.toTelemetryConfigOverride(): TelemetryConfigOverride = TelemetryConfigOverride(
    enabled = booleanField("enabled")?.let(::TelemetryEnabled),
    scopes = stringField("scopes")?.let(::TelemetryScopes),
    detail = stringField("detail")?.let(::TelemetryDetail),
    outputFile = optionalStringField("outputFile")?.let(::TelemetryOutputFile),
)

private fun MapNode.toProfilingConfigOverride(): ProfilingConfigOverride = ProfilingConfigOverride(
    enabled = booleanField("enabled")?.let(::ProfilingEnabled),
    modes = stringField("modes")?.let(::ProfilingModes),
    durationSeconds = longField("durationSeconds")?.let(::ProfilingDurationSeconds),
    outputDir = stringField("outputDir")?.let(::ProfilingOutputDir),
    otlpEndpoint = optionalStringField("otlpEndpoint")?.let(::ProfilingOtlpEndpoint),
    emitManifest = booleanField("emitManifest")?.let(::ProfilingEmitManifest),
)

private fun MapNode.toBackendsConfigOverride(): BackendsConfigOverride = BackendsConfigOverride(
    standalone = childMap("standalone")?.toStandaloneBackendConfigOverride(),
    intellij = childMap("intellij")?.toIntellijBackendConfigOverride(),
)

private fun MapNode.toStandaloneBackendConfigOverride(): StandaloneBackendConfigOverride = StandaloneBackendConfigOverride(
    enabled = booleanField("enabled")?.let(::StandaloneBackendEnabled),
    runtimeLibsDir = optionalStringField("runtimeLibsDir")?.let(::StandaloneRuntimeLibsDir),
)

private fun MapNode.toIntellijBackendConfigOverride(): IntellijBackendConfigOverride = IntellijBackendConfigOverride(
    enabled = booleanField("enabled")?.let(::IntellijBackendEnabled),
)

private fun MapNode.toPathsConfigOverride(): PathsConfigOverride = PathsConfigOverride(
    installRoot = stringField("installRoot")?.let(::PathsInstallRoot),
    binDir = stringField("binDir")?.let(::PathsBinDir),
    libDir = stringField("libDir")?.let(::PathsLibDir),
    cacheDir = stringField("cacheDir")?.let(::PathsCacheDir),
    logsDir = stringField("logsDir")?.let(::PathsLogsDir),
    descriptorDir = stringField("descriptorDir")?.let(::PathsDescriptorDir),
    socketDir = stringField("socketDir")?.let(::PathsSocketDir),
)

private fun MapNode.toCliConfigOverride(): CliConfigOverride = CliConfigOverride(
    binaryPath = stringField("binaryPath")?.let(::CliBinaryPath),
)

private fun MapNode.childMap(key: String): MapNode? = nodeAt(key) as? MapNode

private fun MapNode.nodeAt(key: String): Node? {
    val normalized = key.normalizedConfigKey()
    return map[normalized] ?: map.entries.firstOrNull { (entryKey, _) ->
        entryKey.normalizedConfigKey() == normalized
    }?.value
}

private fun MapNode.stringField(key: String): String? = nodeAt(key)?.asString(key)

private fun MapNode.optionalStringField(key: String): OptionalConfigString? = stringField(key)?.let(::OptionalConfigString)

private fun MapNode.booleanField(key: String): Boolean? = nodeAt(key)?.asBoolean(key)

private fun MapNode.longField(key: String): Long? = nodeAt(key)?.asLong(key)

private fun MapNode.intField(key: String): Int? = nodeAt(key)?.asInt(key)

private fun Node.asString(key: String): String = when (this) {
    is StringNode -> value
    is BooleanNode -> value.toString()
    is LongNode -> value.toString()
    is DoubleNode -> value.toString()
    else -> unsupportedConfigValue(key, "a string")
}

private fun Node.asBoolean(key: String): Boolean = when (this) {
    is BooleanNode -> value
    is StringNode -> when (value.lowercase()) {
        "true", "t", "1", "yes" -> true
        "false", "f", "0", "no" -> false
        else -> unsupportedConfigValue(key, "a boolean")
    }
    else -> unsupportedConfigValue(key, "a boolean")
}

private fun Node.asLong(key: String): Long = when (this) {
    is LongNode -> value
    is StringNode -> value.toLongOrNull() ?: unsupportedConfigValue(key, "a long integer")
    else -> unsupportedConfigValue(key, "a long integer")
}

private fun Node.asInt(key: String): Int = when (this) {
    is LongNode -> value.toInt().takeIf { it.toLong() == value } ?: unsupportedConfigValue(key, "an integer")
    is DoubleNode -> value.toInt()
    is StringNode -> value.toIntOrNull() ?: unsupportedConfigValue(key, "an integer")
    else -> unsupportedConfigValue(key, "an integer")
}

private fun Node.unsupportedConfigValue(
    key: String,
    expected: String,
): Nothing {
    val path = path.flatten().ifBlank { key }
    throw IllegalArgumentException(
        "Config value '$path' at ${pos.loc()} must be $expected, but was $simpleName",
    )
}

private fun String.normalizedConfigKey(): String = replace("-", "").replace("_", "").lowercase()

private fun KastConfig.merge(override: KastConfigOverride): KastConfig {
    val mergedPaths = paths.merge(override.paths)
    val binDirChanged = mergedPaths.binDir != paths.binDir
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
        cli = cli.merge(override.cli, mergedPaths, binDirChanged),
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
    maxIncludedProjects = override?.maxIncludedProjects ?: maxIncludedProjects,
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
    standalone = standalone.merge(override?.standalone, paths),
    intellij = intellij.merge(override?.intellij),
)

private fun StandaloneBackendConfig.merge(
    override: StandaloneBackendConfigOverride?,
    paths: PathsConfig,
): StandaloneBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
    runtimeLibsDir = override?.runtimeLibsDir ?: runtimeLibsDir,
)

private fun IntellijBackendConfig.merge(override: IntellijBackendConfigOverride?): IntellijBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
)

private fun PathsConfig.merge(override: PathsConfigOverride?): PathsConfig {
    if (override == null) return this
    val mergedInstallRoot = override.installRoot ?: installRoot
    val installRootChanged = override.installRoot != null && override.installRoot != installRoot
    val mergedBinDir = override.binDir ?: if (installRootChanged) {
        PathsBinDir(defaultConfigBinDir(mergedInstallRoot.value).toString())
    } else {
        binDir
    }
    val mergedLibDir = override.libDir ?: if (installRootChanged) {
        PathsLibDir(defaultConfigLibDir(mergedInstallRoot.value).toString())
    } else {
        libDir
    }
    val mergedCacheDir = override.cacheDir ?: if (installRootChanged) {
        PathsCacheDir(defaultConfigCacheDir(mergedInstallRoot.value).toString())
    } else {
        cacheDir
    }
    return copy(
        installRoot = mergedInstallRoot,
        binDir = mergedBinDir,
        libDir = mergedLibDir,
        cacheDir = mergedCacheDir,
        logsDir = override.logsDir ?: if (installRootChanged) {
            PathsLogsDir(defaultConfigLogsDir(mergedInstallRoot.value).toString())
        } else {
            logsDir
        },
        descriptorDir = override.descriptorDir ?: if (installRootChanged || override.cacheDir != null) {
            PathsDescriptorDir(defaultConfigDescriptorDir(mergedCacheDir.value).toString())
        } else {
            descriptorDir
        },
        socketDir = override.socketDir ?: socketDir,
    )
}

private fun CliConfig.merge(
    override: CliConfigOverride?,
    paths: PathsConfig,
    binDirChanged: Boolean,
): CliConfig = copy(
    binaryPath = override?.binaryPath ?: if (binDirChanged) {
        CliBinaryPath(defaultConfigCliBinaryPath(paths.binDir.value).toString())
    } else {
        binaryPath
    },
)
