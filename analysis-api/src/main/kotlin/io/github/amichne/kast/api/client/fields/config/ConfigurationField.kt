package io.github.amichne.kast.api.client.fields

sealed class ConfigurationField<T> {
    abstract val section: String
    abstract val key: String
    abstract val default: ConfigurationDefault<T>
    abstract val value: T

    companion object {
        fun defaultFields(): List<ConfigurationField<*>> = listOf(
            ServerMaxResults(500),
            ServerRequestTimeoutMillis(30_000L),
            ServerMaxConcurrentRequests(4),
            RelationshipIndexingEnabled(true),
            RelationshipIndexingBatchSize(50),
            RelationshipIndexingParallelism(4),
            RelationshipIndexingModulePriorityDepth(2),
            IndexingCriticalPaths(emptyList()),
            IndexingIgnoredPaths(emptyList()),
            GraphIndexingBatchSize(32),
            IndexingIdentifierIndexWaitMillis(10_000L),
            IndexingRemoteEnabled(false),
            IndexingRemoteSourceIndexUrl(OptionalConfigString.Unset),
            CacheEnabled(true),
            CacheWriteDelayMillis(5_000L),
            CacheSourceIndexSaveDelayMillis(5_000L),
            WatcherDebounceMillis(200L),
            GradleToolingApiTimeoutMillis(120_000L),
            TelemetryEnabled(false),
            TelemetryScopes("all"),
            TelemetryDetail("basic"),
            TelemetryOutputFile(OptionalConfigString.Unset),
            ProfilingEnabled(false),
            ProfilingModes("cpu"),
            ProfilingDurationSeconds(30L),
            ProfilingOutputDir("{logsDir}/profiling"),
            ProfilingOtlpEndpoint(OptionalConfigString.Unset),
            ProfilingEmitManifest(true),
            CodexHooksEnabled(true),
            CodexSessionStartEnabled(true),
            CodexPostToolUseEnabled(true),
            CodexAutoStartIndexer(IndexerAutoStartConsent.Unconfigured),
            PathsInstallRoot(defaultConfigInstallRoot().toString()),
            PathsBinDir(defaultConfigBinDir().toString()),
            PathsLibDir(defaultConfigLibDir().toString()),
            PathsCacheDir(defaultConfigCacheDir().toString()),
            PathsLogsDir(defaultConfigLogsDir().toString()),
            PathsRuntimeDir(defaultConfigRuntimeDir().toString()),
            PathsDescriptorDir(defaultConfigDescriptorDir().toString()),
            PathsSocketDir(defaultConfigSocketDir()),
            CliBinaryPath(defaultConfigCliBinaryPath().toString()),
        )
    }
}
