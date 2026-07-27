package io.github.amichne.kast.api.client

internal fun KastConfig.merge(override: KastConfigOverride): KastConfig {
    val mergedPaths = paths.merge(override.paths)
    return copy(
        server = server.merge(override.server),
        runtime = runtime.merge(override.runtime),
        projectOpen = projectOpen.merge(override.projectOpen),
        codex = codex.merge(override.codex),
        indexing = indexing.merge(override.indexing),
        cache = cache.merge(override.cache),
        watcher = watcher.merge(override.watcher),
        gradle = gradle.merge(override.gradle),
        telemetry = telemetry.merge(override.telemetry),
        profiling = profiling.merge(override.profiling),
        backends = backends.merge(override.backends),
        paths = mergedPaths,
        cli = cli.merge(override.cli),
    )
}

private fun CodexConfig.merge(override: CodexConfigOverride?): CodexConfig = copy(
    hooks = hooks.merge(override?.hooks),
)

private fun CodexHooksConfig.merge(override: CodexHooksConfigOverride?): CodexHooksConfig = copy(
    enabled = override?.enabled ?: enabled,
    sessionStart = override?.sessionStart ?: sessionStart,
    postToolUse = override?.postToolUse ?: postToolUse,
)

internal fun KastConfigOverride.ideaWorkspaceOverride(): KastConfigOverride = KastConfigOverride(
    runtime = runtime?.let {
        RuntimeConfigOverride(
            defaultBackend = it.defaultBackend,
            strictPluginMatching = it.strictPluginMatching,
        )
    },
    projectOpen = projectOpen,
    backends = BackendsConfigOverride(idea = backends?.idea).takeIf { it.idea != null },
)

private fun ServerConfig.merge(override: ServerConfigOverride?): ServerConfig = copy(
    maxResults = override?.maxResults ?: maxResults,
    requestTimeoutMillis = override?.requestTimeoutMillis ?: requestTimeoutMillis,
    maxConcurrentRequests = override?.maxConcurrentRequests ?: maxConcurrentRequests,
)

private fun RuntimeConfig.merge(override: RuntimeConfigOverride?): RuntimeConfig = copy(
    defaultBackend = override?.defaultBackend ?: defaultBackend,
    strictPluginMatching = override?.strictPluginMatching ?: strictPluginMatching,
    ideaLaunch = ideaLaunch.merge(override?.ideaLaunch),
)

private fun ProjectOpenConfig.merge(override: ProjectOpenConfigOverride?): ProjectOpenConfig = copy(
    profileAutoInit = override?.profileAutoInit ?: profileAutoInit,
    profile = override?.profile ?: profile,
    autoExcludeGit = override?.autoExcludeGit ?: autoExcludeGit,
    gradleLoadEnabled = override?.gradleLoadEnabled ?: gradleLoadEnabled,
)

private fun IdeaLaunchConfig.merge(override: IdeaLaunchConfigOverride?): IdeaLaunchConfig = copy(
    enabled = override?.enabled ?: enabled,
    command = override?.command ?: command,
    waitTimeoutMillis = override?.waitTimeoutMillis ?: waitTimeoutMillis,
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
): BackendsConfig = copy(
    headless = headless.merge(override?.headless),
    idea = idea.merge(override?.idea),
)

private fun HeadlessBackendConfig.merge(override: HeadlessBackendConfigOverride?): HeadlessBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
)

private fun IdeaBackendConfig.merge(override: IdeaBackendConfigOverride?): IdeaBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
)

private fun PathsConfig.merge(override: PathsConfigOverride?): PathsConfig = copy(
    installRoot = override?.installRoot ?: installRoot,
    binDir = override?.binDir ?: binDir,
    libDir = override?.libDir ?: libDir,
    cacheDir = override?.cacheDir ?: cacheDir,
    logsDir = override?.logsDir ?: logsDir,
    runtimeDir = override?.runtimeDir ?: runtimeDir,
    descriptorDir = override?.descriptorDir ?: descriptorDir,
    socketDir = override?.socketDir ?: socketDir,
)

private fun CliConfig.merge(override: CliConfigOverride?): CliConfig = copy(
    binaryPath = override?.binaryPath ?: binaryPath,
)
