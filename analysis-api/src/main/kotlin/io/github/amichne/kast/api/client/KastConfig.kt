package io.github.amichne.kast.api.client

import com.sksamuel.hoplite.ConfigLoaderBuilder
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
    val backends: BackendsConfig,
) {
    fun toServerLimits(): ServerLimits = ServerLimits(
        maxResults = server.maxResults,
        requestTimeoutMillis = server.requestTimeoutMillis,
        maxConcurrentRequests = server.maxConcurrentRequests,
    )

    companion object {
        fun defaults(): KastConfig = KastConfig(
            server = ServerConfig(
                maxResults = 500,
                requestTimeoutMillis = 30_000L,
                maxConcurrentRequests = 4,
            ),
            indexing = IndexingConfig(
                phase2Enabled = true,
                phase2BatchSize = 50,
                identifierIndexWaitMillis = 10_000L,
                referenceBatchSize = 50,
            ),
            cache = CacheConfig(
                enabled = true,
                writeDelayMillis = 5_000L,
                sourceIndexSaveDelayMillis = 5_000L,
            ),
            watcher = WatcherConfig(debounceMillis = 200L),
            gradle = GradleConfig(
                toolingApiTimeoutMillis = 60_000L,
                maxIncludedProjects = 200,
            ),
            telemetry = TelemetryConfig(
                enabled = false,
                scopes = "all",
                detail = "basic",
                outputFile = null,
            ),
            backends = BackendsConfig(
                standalone = StandaloneBackendConfig(
                    enabled = true,
                    runtimeLibsDir = null,
                ),
                intellij = IntellijBackendConfig(enabled = true),
            ),
        )

        fun load(
            workspaceRoot: Path,
            configHome: () -> Path = { kastConfigHome() },
            workspaceDirectoryResolver: WorkspaceDirectoryResolver = WorkspaceDirectoryResolver(configHome = configHome),
            overrides: KastConfigOverride = KastConfigOverride(),
        ): KastConfig {
            val configFiles = listOf(
                workspaceDirectoryResolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml"),
                configHome().resolve("config.toml"),
            ).filter(Files::isRegularFile).map(Path::toString)
            val loaded = if (configFiles.isEmpty()) {
                KastConfigOverride()
            } else {
                ConfigLoaderBuilder.empty()
                    .addDefaultDecoders()
                    .addDefaultPreprocessors()
                    .addDefaultNodeTransformers()
                    .addDefaultParamMappers()
                    .addDefaultParsers()
                    .allowEmptyConfigFiles()
                    .build()
                    .loadConfigOrThrow<KastConfigOverride>(configFiles)
            }
            return defaults().merge(loaded).merge(overrides)
        }
    }
}

data class ServerConfig(
    val maxResults: Int,
    val requestTimeoutMillis: Long,
    val maxConcurrentRequests: Int,
)

data class IndexingConfig(
    val phase2Enabled: Boolean,
    val phase2BatchSize: Int,
    val identifierIndexWaitMillis: Long,
    val referenceBatchSize: Int,
)

data class CacheConfig(
    val enabled: Boolean,
    val writeDelayMillis: Long,
    val sourceIndexSaveDelayMillis: Long,
)

data class WatcherConfig(
    val debounceMillis: Long,
)

data class GradleConfig(
    val toolingApiTimeoutMillis: Long,
    val maxIncludedProjects: Int,
)

data class TelemetryConfig(
    val enabled: Boolean,
    val scopes: String,
    val detail: String,
    val outputFile: String?,
)

data class BackendsConfig(
    val standalone: StandaloneBackendConfig,
    val intellij: IntellijBackendConfig,
)

data class StandaloneBackendConfig(
    val enabled: Boolean,
    val runtimeLibsDir: String?,
)

data class IntellijBackendConfig(
    val enabled: Boolean,
)

data class KastConfigOverride(
    val server: ServerConfigOverride? = null,
    val indexing: IndexingConfigOverride? = null,
    val cache: CacheConfigOverride? = null,
    val watcher: WatcherConfigOverride? = null,
    val gradle: GradleConfigOverride? = null,
    val telemetry: TelemetryConfigOverride? = null,
    val backends: BackendsConfigOverride? = null,
)

data class ServerConfigOverride(
    val maxResults: Int? = null,
    val requestTimeoutMillis: Long? = null,
    val maxConcurrentRequests: Int? = null,
)

data class IndexingConfigOverride(
    val phase2Enabled: Boolean? = null,
    val phase2BatchSize: Int? = null,
    val identifierIndexWaitMillis: Long? = null,
    val referenceBatchSize: Int? = null,
)

data class CacheConfigOverride(
    val enabled: Boolean? = null,
    val writeDelayMillis: Long? = null,
    val sourceIndexSaveDelayMillis: Long? = null,
)

data class WatcherConfigOverride(
    val debounceMillis: Long? = null,
)

data class GradleConfigOverride(
    val toolingApiTimeoutMillis: Long? = null,
    val maxIncludedProjects: Int? = null,
)

data class TelemetryConfigOverride(
    val enabled: Boolean? = null,
    val scopes: String? = null,
    val detail: String? = null,
    val outputFile: String? = null,
)

data class BackendsConfigOverride(
    val standalone: StandaloneBackendConfigOverride? = null,
    val intellij: IntellijBackendConfigOverride? = null,
)

data class StandaloneBackendConfigOverride(
    val enabled: Boolean? = null,
    val runtimeLibsDir: String? = null,
)

data class IntellijBackendConfigOverride(
    val enabled: Boolean? = null,
)

private fun KastConfig.merge(override: KastConfigOverride): KastConfig = copy(
    server = server.merge(override.server),
    indexing = indexing.merge(override.indexing),
    cache = cache.merge(override.cache),
    watcher = watcher.merge(override.watcher),
    gradle = gradle.merge(override.gradle),
    telemetry = telemetry.merge(override.telemetry),
    backends = backends.merge(override.backends),
)

private fun ServerConfig.merge(override: ServerConfigOverride?): ServerConfig = copy(
    maxResults = override?.maxResults ?: maxResults,
    requestTimeoutMillis = override?.requestTimeoutMillis ?: requestTimeoutMillis,
    maxConcurrentRequests = override?.maxConcurrentRequests ?: maxConcurrentRequests,
)

private fun IndexingConfig.merge(override: IndexingConfigOverride?): IndexingConfig = copy(
    phase2Enabled = override?.phase2Enabled ?: phase2Enabled,
    phase2BatchSize = override?.phase2BatchSize ?: phase2BatchSize,
    identifierIndexWaitMillis = override?.identifierIndexWaitMillis ?: identifierIndexWaitMillis,
    referenceBatchSize = override?.referenceBatchSize ?: referenceBatchSize,
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

private fun BackendsConfig.merge(override: BackendsConfigOverride?): BackendsConfig = copy(
    standalone = standalone.merge(override?.standalone),
    intellij = intellij.merge(override?.intellij),
)

private fun StandaloneBackendConfig.merge(override: StandaloneBackendConfigOverride?): StandaloneBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
    runtimeLibsDir = override?.runtimeLibsDir ?: runtimeLibsDir,
)

private fun IntellijBackendConfig.merge(override: IntellijBackendConfigOverride?): IntellijBackendConfig = copy(
    enabled = override?.enabled ?: enabled,
)
