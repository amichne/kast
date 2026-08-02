package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
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
    val paths: PathsConfig,
    val cli: CliConfig,
    val codex: CodexConfig = CodexConfig(),
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
            val resolvedPaths = resolveKastPathDefaults()
            val paths = PathsConfig(
                installRoot = PathsInstallRoot(resolvedPaths.installRoot.toString()),
                binDir = PathsBinDir(resolvedPaths.binDir.toString()),
                libDir = PathsLibDir(resolvedPaths.libDir.toString()),
                cacheDir = PathsCacheDir(resolvedPaths.cacheDir.toString()),
                logsDir = PathsLogsDir(resolvedPaths.logsDir.toString()),
                runtimeDir = PathsRuntimeDir(resolvedPaths.runtimeDir.toString()),
                descriptorDir = PathsDescriptorDir(resolvedPaths.descriptorDir.toString()),
                socketDir = PathsSocketDir(resolvedPaths.socketDir.toString()),
            )
            return KastConfig(
                server = ServerConfig(
                    maxResults = ServerMaxResults(500),
                    requestTimeoutMillis = ServerRequestTimeoutMillis(30_000L),
                    maxConcurrentRequests = ServerMaxConcurrentRequests(4),
                ),
                codex = CodexConfig(
                    hooks = CodexHooksConfig(
                        enabled = CodexHooksEnabled(true),
                        sessionStart = CodexSessionStartEnabled(true),
                        postToolUse = CodexPostToolUseEnabled(true),
                    ),
                ),
                indexing = IndexingConfig(
                    criticalPaths = IndexingCriticalPaths(emptyList()),
                    ignoredPaths = IndexingIgnoredPaths(emptyList()),
                    graph = GraphIndexingConfig(
                        batchSize = GraphIndexingBatchSize(32),
                    ),
                    relationships = RelationshipIndexingConfig(
                        enabled = RelationshipIndexingEnabled(true),
                        batchSize = RelationshipIndexingBatchSize(50),
                        parallelism = RelationshipIndexingParallelism(4),
                        modulePriorityDepth = RelationshipIndexingModulePriorityDepth(2),
                    ),
                    identifierIndexWaitMillis = IndexingIdentifierIndexWaitMillis(10_000L),
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
                paths = paths,
                cli = CliConfig(binaryPath = CliBinaryPath(resolvedPaths.cliBinary.toString())),
            )
        }

        fun load(
            workspaceRoot: Path,
            configHome: () -> Path = { kastConfigHome() },
            workspaceDirectoryResolver: WorkspaceDirectoryResolver = WorkspaceDirectoryResolver(),
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            val configFiles = listOf(
                workspaceDirectoryResolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml"),
                configHome().resolve("config.toml"),
            ).filter(Files::isRegularFile)
            val loaded = loadConfigOverrides(configFiles)
            return defaults().merge(loaded).merge(overrides)
        }

        fun loadGlobal(
            configHome: () -> Path = { kastConfigHome() },
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            val configFiles = listOf(configHome().resolve("config.toml")).filter(Files::isRegularFile)
            return defaults().merge(loadConfigOverrides(configFiles)).merge(overrides)
        }

        /**
         * Loads the Rust CLI's fully resolved runtime configuration handoff.
         *
         * This path intentionally avoids TOML parsing in the JVM backend; the
         * Rust CLI owns config file parsing and passes this JSON to the indexer.
         */
        fun loadResolvedJson(
            configFile: Path,
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            return defaults()
                .mergeResolvedJson(configFile)
                .merge(overrides)
        }
    }
}
