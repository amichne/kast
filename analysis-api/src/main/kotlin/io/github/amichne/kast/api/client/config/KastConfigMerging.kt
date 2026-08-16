package io.github.amichne.kast.api.client

internal fun KastConfig.merge(override: KastConfigOverride): KastConfig {
    val mergedPaths = paths.merge(override.paths)
    return copy(
        server = server.merge(override.server),
        codex = codex.merge(override.codex),
        indexing = indexing.merge(override.indexing),
        cache = cache.merge(override.cache),
        watcher = watcher.merge(override.watcher),
        gradle = gradle.merge(override.gradle),
        telemetry = telemetry.merge(override.telemetry),
        profiling = profiling.merge(override.profiling),
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
    autoStartIndexer = override?.autoStartIndexer ?: autoStartIndexer,
)

private fun ServerConfig.merge(override: ServerConfigOverride?): ServerConfig = copy(
    maxResults = override?.maxResults ?: maxResults,
    requestTimeoutMillis = override?.requestTimeoutMillis ?: requestTimeoutMillis,
    maxConcurrentRequests = override?.maxConcurrentRequests ?: maxConcurrentRequests,
)

private fun IndexingConfig.merge(override: IndexingConfigOverride?): IndexingConfig = copy(
    criticalPaths = override?.criticalPaths ?: criticalPaths,
    ignoredPaths = override?.ignoredPaths ?: ignoredPaths,
    graph = graph.merge(override?.graph),
    relationships = relationships.merge(override?.relationships),
    identifierIndexWaitMillis = override?.identifierIndexWaitMillis ?: identifierIndexWaitMillis,
    remote = remote.merge(override?.remote),
)

private fun GraphIndexingConfig.merge(override: GraphIndexingConfigOverride?): GraphIndexingConfig = copy(
    batchSize = override?.batchSize ?: batchSize,
)

private fun RelationshipIndexingConfig.merge(
    override: RelationshipIndexingConfigOverride?,
): RelationshipIndexingConfig = copy(
    enabled = override?.enabled ?: enabled,
    batchSize = override?.batchSize ?: batchSize,
    parallelism = override?.parallelism ?: parallelism,
    modulePriorityDepth = override?.modulePriorityDepth ?: modulePriorityDepth,
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
