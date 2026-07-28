package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*

data class ServerConfig(
    val maxResults: ServerMaxResults,
    val requestTimeoutMillis: ServerRequestTimeoutMillis,
    val maxConcurrentRequests: ServerMaxConcurrentRequests,
)

data class RuntimeConfig(
    val defaultBackend: RuntimeDefaultBackend,
    val strictPluginMatching: RuntimeStrictPluginMatching,
    val ideaLaunch: IdeaLaunchConfig,
)

data class ProjectOpenConfig(
    val profileAutoInit: ProjectOpenProfileAutoInit,
    val profile: ProjectOpenProfile,
    val autoExcludeGit: ProjectOpenAutoExcludeGit,
    val gradleLoadEnabled: ProjectOpenGradleLoadEnabled,
)

data class IdeaLaunchConfig(
    val enabled: IdeaLaunchEnabled,
    val command: IdeaLaunchCommand,
    val waitTimeoutMillis: IdeaLaunchWaitTimeoutMillis,
)

data class IndexingConfig(
    val relationships: RelationshipIndexingConfig,
    val identifierIndexWaitMillis: IndexingIdentifierIndexWaitMillis,
    val remote: RemoteIndexConfig,
)

data class RelationshipIndexingConfig(
    val enabled: RelationshipIndexingEnabled,
    val batchSize: RelationshipIndexingBatchSize,
    val parallelism: RelationshipIndexingParallelism,
    val modulePriorityDepth: RelationshipIndexingModulePriorityDepth,
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

data class CodexConfig(
    val hooks: CodexHooksConfig = CodexHooksConfig(),
)

data class CodexHooksConfig(
    val enabled: CodexHooksEnabled = CodexHooksEnabled(true),
    val sessionStart: CodexSessionStartEnabled = CodexSessionStartEnabled(true),
    val postToolUse: CodexPostToolUseEnabled = CodexPostToolUseEnabled(true),
)

data class PathsConfig(
    val installRoot: PathsInstallRoot,
    val binDir: PathsBinDir,
    val libDir: PathsLibDir,
    val cacheDir: PathsCacheDir,
    val logsDir: PathsLogsDir,
    val runtimeDir: PathsRuntimeDir,
    val descriptorDir: PathsDescriptorDir,
    val socketDir: PathsSocketDir,
)

data class CliConfig(
    val binaryPath: CliBinaryPath,
)

data class KastConfigOverride(
    val server: ServerConfigOverride? = null,
    val runtime: RuntimeConfigOverride? = null,
    val projectOpen: ProjectOpenConfigOverride? = null,
    val codex: CodexConfigOverride? = null,
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

data class CodexConfigOverride(
    val hooks: CodexHooksConfigOverride? = null,
)

data class CodexHooksConfigOverride(
    val enabled: CodexHooksEnabled? = null,
    val sessionStart: CodexSessionStartEnabled? = null,
    val postToolUse: CodexPostToolUseEnabled? = null,
)

data class ProjectOpenConfigOverride(
    val profileAutoInit: ProjectOpenProfileAutoInit? = null,
    val profile: ProjectOpenProfile? = null,
    val autoExcludeGit: ProjectOpenAutoExcludeGit? = null,
    val gradleLoadEnabled: ProjectOpenGradleLoadEnabled? = null,
)

data class RuntimeConfigOverride(
    val defaultBackend: RuntimeDefaultBackend? = null,
    val strictPluginMatching: RuntimeStrictPluginMatching? = null,
    val ideaLaunch: IdeaLaunchConfigOverride? = null,
)

data class IdeaLaunchConfigOverride(
    val enabled: IdeaLaunchEnabled? = null,
    val command: IdeaLaunchCommand? = null,
    val waitTimeoutMillis: IdeaLaunchWaitTimeoutMillis? = null,
)

data class ServerConfigOverride(
    val maxResults: ServerMaxResults? = null,
    val requestTimeoutMillis: ServerRequestTimeoutMillis? = null,
    val maxConcurrentRequests: ServerMaxConcurrentRequests? = null,
)

data class IndexingConfigOverride(
    val relationships: RelationshipIndexingConfigOverride? = null,
    val identifierIndexWaitMillis: IndexingIdentifierIndexWaitMillis? = null,
    val remote: RemoteIndexConfigOverride? = null,
)

data class RelationshipIndexingConfigOverride(
    val enabled: RelationshipIndexingEnabled? = null,
    val batchSize: RelationshipIndexingBatchSize? = null,
    val parallelism: RelationshipIndexingParallelism? = null,
    val modulePriorityDepth: RelationshipIndexingModulePriorityDepth? = null,
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
    val runtimeDir: PathsRuntimeDir? = null,
    val descriptorDir: PathsDescriptorDir? = null,
    val socketDir: PathsSocketDir? = null,
)

data class CliConfigOverride(
    val binaryPath: CliBinaryPath? = null,
)
