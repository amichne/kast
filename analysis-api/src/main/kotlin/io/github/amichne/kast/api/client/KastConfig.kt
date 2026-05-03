package io.github.amichne.kast.api.client

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import io.github.amichne.kast.api.contract.ServerLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

const val KAST_CONFIG_PATH = "KAST_CONFIG_PATH"

interface Toggleable {
    val enabled: Boolean

    object Enabled : Toggleable {
        override val enabled: Boolean = true
    }

    object Disabled : Toggleable {
        override val enabled: Boolean = false
    }
}

interface Timeout {
    val timeout: Duration
}

data class KastConfig(
    val server: ServerConfig = ServerConfig(),
    val indexing: IndexingConfig = IndexingConfig(),
    val cache: CacheConfig = CacheConfig(),
    val watcher: WatcherConfig = WatcherConfig(),
    val gradle: GradleConfig = GradleConfig(),
    val telemetry: TelemetryConfig = TelemetryConfig(),
    val backends: BackendsConfig = BackendsConfig(),
) {
    fun toServerLimits(): ServerLimits = ServerLimits(
        maxResults = server.maxResults,
        requestTimeoutMillis = server.requestTimeoutMillis,
        maxConcurrentRequests = server.maxConcurrentRequests,
    )

    companion object {
        fun defaults(): KastConfig = KastConfig()

        @Suppress("UNUSED_PARAMETER")
        fun load(
            workspaceRoot: Path,
            configHome: () -> Path = { kastConfigHome() },
            workspaceDirectoryResolver: WorkspaceDirectoryResolver = WorkspaceDirectoryResolver(configHome = configHome),
            overrides: KastConfigOverride = KastConfigOverride(),
            envReader: (String) -> String? = System::getenv,
            configPath: Path? = configPathFromEnvironment(envReader),
        ): KastConfig {
            val loaded = configPath?.let(::loadConfigFile) ?: KastConfigOverride()
            return defaults().merge(loaded).merge(overrides)
        }

        private fun configPathFromEnvironment(envReader: (String) -> String?): Path? =
            envReader(KAST_CONFIG_PATH)
                ?.takeIf(String::isNotBlank)
                ?.let { Path.of(it).toAbsolutePath().normalize() }

        @OptIn(ExperimentalHoplite::class)
        private fun loadConfigFile(configPath: Path): KastConfigOverride {
            require(Files.isRegularFile(configPath)) {
                "$KAST_CONFIG_PATH must point to a regular file: $configPath"
            }
            return ConfigLoaderBuilder.empty()
                .withClassLoader(KastConfig::class.java.classLoader)
                .addDefaultDecoders()
                .addDefaultPreprocessors()
                .addDefaultNodeTransformers()
                .addDefaultParamMappers()
                .addDefaultParsers()
                .withExplicitSealedTypes()
                .allowEmptyConfigFiles()
                .build()
                .loadConfigOrThrow<KastConfigOverride>(listOf(configPath.toString()))
        }
    }
}

data class ServerConfig(
    val maxResults: Int = 500,
    val requestTimeoutMillis: Long = 30.seconds.inWholeMilliseconds,
    val maxConcurrentRequests: Int = 4,
) : Timeout {
    override val timeout: Duration
        get() = requestTimeoutMillis.milliseconds
}

data class IndexingConfig(
    val phase2Enabled: Boolean = true,
    val phase2BatchSize: Int = 50,
    val identifierIndexWaitMillis: Long = 10.seconds.inWholeMilliseconds,
    val referenceBatchSize: Int = 50,
    val remote: RemoteIndexConfig = RemoteIndexConfig(),
)

data class RemoteIndexConfig(
    override val enabled: Boolean = Toggleable.Disabled.enabled,
    val sourceIndexUrl: String? = null,
) : Toggleable

data class CacheConfig(
    override val enabled: Boolean = Toggleable.Enabled.enabled,
    val writeDelayMillis: Long = 5.seconds.inWholeMilliseconds,
    val sourceIndexSaveDelayMillis: Long = 5.seconds.inWholeMilliseconds,
) : Toggleable

data class WatcherConfig(
    val debounceMillis: Long = 200L,
)

data class GradleConfig(
    val toolingApiTimeoutMillis: Long = 60.seconds.inWholeMilliseconds,
    val maxIncludedProjects: Int = 200,
) : Timeout {
    override val timeout: Duration
        get() = toolingApiTimeoutMillis.milliseconds
}

data class TelemetryConfig(
    override val enabled: Boolean = Toggleable.Disabled.enabled,
    val scopes: String = "all",
    val detail: String = "basic",
    val outputFile: String? = null,
) : Toggleable

data class BackendsConfig(
    val standalone: StandaloneBackendConfig = StandaloneBackendConfig(),
    val intellij: IntellijBackendConfig = IntellijBackendConfig(),
)

data class StandaloneBackendConfig(
    override val enabled: Boolean = Toggleable.Enabled.enabled,
    val runtimeLibsDir: String? = null,
) : Toggleable

data class IntellijBackendConfig(
    override val enabled: Boolean = Toggleable.Enabled.enabled,
) : Toggleable

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
    val remote: RemoteIndexConfigOverride? = null,
)

data class RemoteIndexConfigOverride(
    val enabled: Boolean? = null,
    val sourceIndexUrl: String? = null,
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
