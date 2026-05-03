package io.github.amichne.kast.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.amichne.kast.api.client.BackendsConfigOverride
import io.github.amichne.kast.api.client.CacheConfigOverride
import io.github.amichne.kast.api.client.GradleConfigOverride
import io.github.amichne.kast.api.client.IndexingConfigOverride
import io.github.amichne.kast.api.client.IntellijBackendConfigOverride
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.RemoteIndexConfigOverride
import io.github.amichne.kast.api.client.ServerConfigOverride
import io.github.amichne.kast.api.client.StandaloneBackendConfigOverride
import io.github.amichne.kast.api.client.TelemetryConfigOverride
import io.github.amichne.kast.api.client.WatcherConfigOverride

@State(name = "KastSettings", storages = [Storage("kast.xml")])
@Service(Service.Level.PROJECT)
internal class KastSettingsState : PersistentStateComponent<KastSettingsState> {
    var serverMaxResults: Int? = null
    var serverRequestTimeoutMillis: Long? = null
    var serverMaxConcurrentRequests: Int? = null
    var indexingPhase2Enabled: Boolean? = null
    var indexingPhase2BatchSize: Int? = null
    var indexingIdentifierIndexWaitMillis: Long? = null
    var indexingReferenceBatchSize: Int? = null
    var indexingRemoteEnabled: Boolean? = null
    var indexingRemoteSourceIndexUrl: String? = null
    var cacheEnabled: Boolean? = null
    var cacheWriteDelayMillis: Long? = null
    var cacheSourceIndexSaveDelayMillis: Long? = null
    var watcherDebounceMillis: Long? = null
    var gradleToolingApiTimeoutMillis: Long? = null
    var gradleMaxIncludedProjects: Int? = null
    var telemetryEnabled: Boolean? = null
    var telemetryScopes: String? = null
    var telemetryDetail: String? = null
    var telemetryOutputFile: String? = null
    var backendsStandaloneEnabled: Boolean? = null
    var backendsStandaloneRuntimeLibsDir: String? = null
    var backendsIntellijEnabled: Boolean? = null

    override fun getState(): KastSettingsState = this

    override fun loadState(state: KastSettingsState) = XmlSerializerUtil.copyBean(state, this)

    fun loadFromConfig(config: KastConfig) {
        serverMaxResults = config.server.maxResults
        serverRequestTimeoutMillis = config.server.requestTimeoutMillis
        serverMaxConcurrentRequests = config.server.maxConcurrentRequests
        indexingPhase2Enabled = config.indexing.phase2Enabled
        indexingPhase2BatchSize = config.indexing.phase2BatchSize
        indexingIdentifierIndexWaitMillis = config.indexing.identifierIndexWaitMillis
        indexingReferenceBatchSize = config.indexing.referenceBatchSize
        indexingRemoteEnabled = config.indexing.remote.enabled
        indexingRemoteSourceIndexUrl = config.indexing.remote.sourceIndexUrl
        cacheEnabled = config.cache.enabled
        cacheWriteDelayMillis = config.cache.writeDelayMillis
        cacheSourceIndexSaveDelayMillis = config.cache.sourceIndexSaveDelayMillis
        watcherDebounceMillis = config.watcher.debounceMillis
        gradleToolingApiTimeoutMillis = config.gradle.toolingApiTimeoutMillis
        gradleMaxIncludedProjects = config.gradle.maxIncludedProjects
        telemetryEnabled = config.telemetry.enabled
        telemetryScopes = config.telemetry.scopes
        telemetryDetail = config.telemetry.detail
        telemetryOutputFile = config.telemetry.outputFile
        backendsStandaloneEnabled = config.backends.standalone.enabled
        backendsStandaloneRuntimeLibsDir = config.backends.standalone.runtimeLibsDir
        backendsIntellijEnabled = config.backends.intellij.enabled
    }

    fun toOverride(): KastConfigOverride = KastConfigOverride(
        server = ServerConfigOverride(
            maxResults = serverMaxResults,
            requestTimeoutMillis = serverRequestTimeoutMillis,
            maxConcurrentRequests = serverMaxConcurrentRequests,
        ).takeIfAny(),
        indexing = IndexingConfigOverride(
            phase2Enabled = indexingPhase2Enabled,
            phase2BatchSize = indexingPhase2BatchSize,
            identifierIndexWaitMillis = indexingIdentifierIndexWaitMillis,
            referenceBatchSize = indexingReferenceBatchSize,
            remote = RemoteIndexConfigOverride(
                enabled = indexingRemoteEnabled,
                sourceIndexUrl = indexingRemoteSourceIndexUrl?.takeIf(String::isNotBlank),
            ).takeIfAny(),
        ).takeIfAny(),
        cache = CacheConfigOverride(
            enabled = cacheEnabled,
            writeDelayMillis = cacheWriteDelayMillis,
            sourceIndexSaveDelayMillis = cacheSourceIndexSaveDelayMillis,
        ).takeIfAny(),
        watcher = WatcherConfigOverride(debounceMillis = watcherDebounceMillis).takeIfAny(),
        gradle = GradleConfigOverride(
            toolingApiTimeoutMillis = gradleToolingApiTimeoutMillis,
            maxIncludedProjects = gradleMaxIncludedProjects,
        ).takeIfAny(),
        telemetry = TelemetryConfigOverride(
            enabled = telemetryEnabled,
            scopes = telemetryScopes?.takeIf(String::isNotBlank),
            detail = telemetryDetail?.takeIf(String::isNotBlank),
            outputFile = telemetryOutputFile?.takeIf(String::isNotBlank),
        ).takeIfAny(),
        backends = BackendsConfigOverride(
            standalone = StandaloneBackendConfigOverride(
                enabled = backendsStandaloneEnabled,
                runtimeLibsDir = backendsStandaloneRuntimeLibsDir?.takeIf(String::isNotBlank),
            ).takeIfAny(),
            intellij = IntellijBackendConfigOverride(enabled = backendsIntellijEnabled).takeIfAny(),
        ).takeIfAny(),
    )

    companion object {
        fun getInstance(project: Project): KastSettingsState = project.service()
    }
}

private fun ServerConfigOverride.takeIfAny(): ServerConfigOverride? =
    takeIf { maxResults != null || requestTimeoutMillis != null || maxConcurrentRequests != null }

private fun IndexingConfigOverride.takeIfAny(): IndexingConfigOverride? =
    takeIf {
        phase2Enabled != null ||
            phase2BatchSize != null ||
            identifierIndexWaitMillis != null ||
            referenceBatchSize != null ||
            remote != null
    }

private fun RemoteIndexConfigOverride.takeIfAny(): RemoteIndexConfigOverride? =
    takeIf { enabled != null || sourceIndexUrl != null }

private fun CacheConfigOverride.takeIfAny(): CacheConfigOverride? =
    takeIf { enabled != null || writeDelayMillis != null || sourceIndexSaveDelayMillis != null }

private fun WatcherConfigOverride.takeIfAny(): WatcherConfigOverride? =
    takeIf { debounceMillis != null }

private fun GradleConfigOverride.takeIfAny(): GradleConfigOverride? =
    takeIf { toolingApiTimeoutMillis != null || maxIncludedProjects != null }

private fun TelemetryConfigOverride.takeIfAny(): TelemetryConfigOverride? =
    takeIf { enabled != null || scopes != null || detail != null || outputFile != null }

private fun BackendsConfigOverride.takeIfAny(): BackendsConfigOverride? =
    takeIf { standalone != null || intellij != null }

private fun StandaloneBackendConfigOverride.takeIfAny(): StandaloneBackendConfigOverride? =
    takeIf { enabled != null || runtimeLibsDir != null }

private fun IntellijBackendConfigOverride.takeIfAny(): IntellijBackendConfigOverride? =
    takeIf { enabled != null }
