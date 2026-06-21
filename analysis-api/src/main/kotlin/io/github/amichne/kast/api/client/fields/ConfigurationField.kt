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
            IndexingPhase2Enabled(true),
            IndexingPhase2BatchSize(50),
            IndexingPhase2Parallelism(4),
            IndexingPhase2PriorityDepth(2),
            IndexingIdentifierIndexWaitMillis(10_000L),
            IndexingReferenceBatchSize(50),
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
            RuntimeDefaultBackend("auto"),
            IdeaLaunchEnabled(false),
            IdeaLaunchCommand("idea"),
            IdeaLaunchWaitTimeoutMillis(90_000L),
            IdeaLaunchRequireInstalledPlugin(true),
            ProjectOpenProfileAutoInit(false),
            ProjectOpenProfile(ProjectOpenProfile.COPILOT_LSP),
            ProjectOpenAutoExcludeGit(true),
            HeadlessBackendEnabled(true),
            HeadlessRuntimeLibsDir(OptionalConfigString(defaultConfigHeadlessRuntimeLibsDir().toString())),
            HeadlessIdeaHome(OptionalConfigString.Unset),
            IdeaBackendEnabled(true),
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
