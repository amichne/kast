package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import io.github.amichne.kast.api.contract.ServerLimits
import java.nio.file.Files
import java.nio.file.Path

data class KastConfig(
    val server: ServerConfig,
    val runtime: RuntimeConfig,
    val projectOpen: ProjectOpenConfig,
    val indexing: IndexingConfig,
    val cache: CacheConfig,
    val watcher: WatcherConfig,
    val gradle: GradleConfig,
    val telemetry: TelemetryConfig,
    val profiling: ProfilingConfig,
    val backends: BackendsConfig,
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
                runtime = RuntimeConfig(
                    defaultBackend = RuntimeDefaultBackend("auto"),
                    strictPluginMatching = RuntimeStrictPluginMatching(true),
                    ideaLaunch = IdeaLaunchConfig(
                        enabled = IdeaLaunchEnabled(false),
                        command = IdeaLaunchCommand("idea"),
                        waitTimeoutMillis = IdeaLaunchWaitTimeoutMillis(90_000L),
                    ),
                ),
                projectOpen = ProjectOpenConfig(
                    profileAutoInit = ProjectOpenProfileAutoInit(true),
                    profile = ProjectOpenProfile(ProjectOpenProfile.JETBRAINS_PLUGIN),
                    autoExcludeGit = ProjectOpenAutoExcludeGit(true),
                    gradleLoadEnabled = ProjectOpenGradleLoadEnabled(true),
                ),
                codex = CodexConfig(
                    hooks = CodexHooksConfig(
                        enabled = CodexHooksEnabled(true),
                        sessionStart = CodexSessionStartEnabled(true),
                        postToolUse = CodexPostToolUseEnabled(true),
                    ),
                ),
                indexing = IndexingConfig(
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
                backends = BackendsConfig(
                    headless = HeadlessBackendConfig(
                        enabled = HeadlessBackendEnabled(true),
                        runtimeLibsDir = HeadlessRuntimeLibsDir(
                            OptionalConfigString(resolvedPaths.headlessRuntimeLibsDir.toString()),
                        ),
                        ideaHome = HeadlessIdeaHome(OptionalConfigString.Unset),
                    ),
                    idea = IdeaBackendConfig(enabled = IdeaBackendEnabled(true)),
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
         * Loads config for the IDEA-hosted backend.
         *
         * Global config remains authoritative for install and runtime paths. The
         * workspace config may only override IDEA-owned logical settings so a
         * project-local config cannot redirect the IDE to a different binary,
         * cache, descriptor, socket, or headless runtime layout.
         */
        fun loadIdea(
            workspaceRoot: Path,
            configHome: () -> Path = { kastConfigHome() },
            workspaceDirectoryResolver: WorkspaceDirectoryResolver = WorkspaceDirectoryResolver(),
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            val globalConfig = configHome().resolve("config.toml")
            val workspaceConfig = workspaceDirectoryResolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml")
            val globalOverrides = listOf(globalConfig)
                .filter(Files::isRegularFile)
                .let(::loadConfigOverrides)
            val workspaceOverrides = listOf(workspaceConfig)
                .filter(Files::isRegularFile)
                .let(::loadConfigOverrides)
                .ideaWorkspaceOverride()
            return defaults()
                .merge(globalOverrides)
                .merge(workspaceOverrides)
                .merge(overrides)
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
            return defaults()
                .mergeResolvedJson(configFile)
                .merge(overrides)
        }
    }
}
