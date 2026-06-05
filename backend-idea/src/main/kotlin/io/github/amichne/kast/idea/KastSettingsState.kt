package io.github.amichne.kast.idea

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
import io.github.amichne.kast.api.client.IdeaBackendConfigOverride
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.fields.CacheEnabled
import io.github.amichne.kast.api.client.fields.CacheSourceIndexSaveDelayMillis
import io.github.amichne.kast.api.client.fields.CacheWriteDelayMillis
import io.github.amichne.kast.api.client.fields.GradleToolingApiTimeoutMillis
import io.github.amichne.kast.api.client.fields.IndexingIdentifierIndexWaitMillis
import io.github.amichne.kast.api.client.fields.IndexingPhase2BatchSize
import io.github.amichne.kast.api.client.fields.IndexingPhase2Enabled
import io.github.amichne.kast.api.client.fields.IndexingPhase2PriorityDepth
import io.github.amichne.kast.api.client.fields.IndexingReferenceBatchSize
import io.github.amichne.kast.api.client.fields.IndexingRemoteEnabled
import io.github.amichne.kast.api.client.fields.IndexingRemoteSourceIndexUrl
import io.github.amichne.kast.api.client.fields.IdeaBackendEnabled
import io.github.amichne.kast.api.client.fields.OptionalConfigString
import io.github.amichne.kast.api.client.fields.ServerMaxConcurrentRequests
import io.github.amichne.kast.api.client.fields.ServerMaxResults
import io.github.amichne.kast.api.client.fields.ServerRequestTimeoutMillis
import io.github.amichne.kast.api.client.fields.HeadlessBackendEnabled
import io.github.amichne.kast.api.client.fields.HeadlessIdeaHome
import io.github.amichne.kast.api.client.fields.HeadlessRuntimeLibsDir
import io.github.amichne.kast.api.client.fields.TelemetryDetail
import io.github.amichne.kast.api.client.fields.TelemetryEnabled
import io.github.amichne.kast.api.client.fields.TelemetryOutputFile
import io.github.amichne.kast.api.client.fields.TelemetryScopes
import io.github.amichne.kast.api.client.fields.WatcherDebounceMillis
import io.github.amichne.kast.api.client.RemoteIndexConfigOverride
import io.github.amichne.kast.api.client.ServerConfigOverride
import io.github.amichne.kast.api.client.HeadlessBackendConfigOverride
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
    var indexingPhase2PriorityDepth: Int? = null
    var indexingIdentifierIndexWaitMillis: Long? = null
    var indexingReferenceBatchSize: Int? = null
    var indexingRemoteEnabled: Boolean? = null
    var indexingRemoteSourceIndexUrl: String? = null
    var cacheEnabled: Boolean? = null
    var cacheWriteDelayMillis: Long? = null
    var cacheSourceIndexSaveDelayMillis: Long? = null
    var watcherDebounceMillis: Long? = null
    var gradleToolingApiTimeoutMillis: Long? = null
    var telemetryEnabled: Boolean? = null
    var telemetryScopes: String? = null
    var telemetryDetail: String? = null
    var telemetryOutputFile: String? = null
    var backendsHeadlessEnabled: Boolean? = null
    var backendsHeadlessRuntimeLibsDir: String? = null
    var backendsHeadlessIdeaHome: String? = null
    var backendsIdeaEnabled: Boolean? = null

    override fun getState(): KastSettingsState = this

    override fun loadState(state: KastSettingsState) = XmlSerializerUtil.copyBean(state, this)

    fun loadFromConfig(config: KastConfig) {
        serverMaxResults = config.server.maxResults.value
        serverRequestTimeoutMillis = config.server.requestTimeoutMillis.value
        serverMaxConcurrentRequests = config.server.maxConcurrentRequests.value
        indexingPhase2Enabled = config.indexing.phase2Enabled.value
        indexingPhase2BatchSize = config.indexing.phase2BatchSize.value
        indexingPhase2PriorityDepth = config.indexing.phase2PriorityDepth.value
        indexingIdentifierIndexWaitMillis = config.indexing.identifierIndexWaitMillis.value
        indexingReferenceBatchSize = config.indexing.referenceBatchSize.value
        indexingRemoteEnabled = config.indexing.remote.enabled.value
        indexingRemoteSourceIndexUrl = config.indexing.remote.sourceIndexUrl.value.orNull
        cacheEnabled = config.cache.enabled.value
        cacheWriteDelayMillis = config.cache.writeDelayMillis.value
        cacheSourceIndexSaveDelayMillis = config.cache.sourceIndexSaveDelayMillis.value
        watcherDebounceMillis = config.watcher.debounceMillis.value
        gradleToolingApiTimeoutMillis = config.gradle.toolingApiTimeoutMillis.value
        telemetryEnabled = config.telemetry.enabled.value
        telemetryScopes = config.telemetry.scopes.value
        telemetryDetail = config.telemetry.detail.value
        telemetryOutputFile = config.telemetry.outputFile.value.orNull
        backendsHeadlessEnabled = config.backends.headless.enabled.value
        backendsHeadlessRuntimeLibsDir = config.backends.headless.runtimeLibsDir.value.orNull
        backendsHeadlessIdeaHome = config.backends.headless.ideaHome.value.orNull
        backendsIdeaEnabled = config.backends.idea.enabled.value
    }

    fun toOverride(): KastConfigOverride = KastConfigOverride(
        server = ServerConfigOverride(
            maxResults = serverMaxResults?.let(::ServerMaxResults),
            requestTimeoutMillis = serverRequestTimeoutMillis?.let(::ServerRequestTimeoutMillis),
            maxConcurrentRequests = serverMaxConcurrentRequests?.let(::ServerMaxConcurrentRequests),
        ).takeIfAny(),
        indexing = IndexingConfigOverride(
            phase2Enabled = indexingPhase2Enabled?.let(::IndexingPhase2Enabled),
            phase2BatchSize = indexingPhase2BatchSize?.let(::IndexingPhase2BatchSize),
            phase2PriorityDepth = indexingPhase2PriorityDepth?.let(::IndexingPhase2PriorityDepth),
            identifierIndexWaitMillis = indexingIdentifierIndexWaitMillis?.let(::IndexingIdentifierIndexWaitMillis),
            referenceBatchSize = indexingReferenceBatchSize?.let(::IndexingReferenceBatchSize),
            remote = RemoteIndexConfigOverride(
                enabled = indexingRemoteEnabled?.let(::IndexingRemoteEnabled),
                sourceIndexUrl = indexingRemoteSourceIndexUrl?.takeIf(String::isNotBlank)?.let {
                    IndexingRemoteSourceIndexUrl(OptionalConfigString(it))
                },
            ).takeIfAny(),
        ).takeIfAny(),
        cache = CacheConfigOverride(
            enabled = cacheEnabled?.let(::CacheEnabled),
            writeDelayMillis = cacheWriteDelayMillis?.let(::CacheWriteDelayMillis),
            sourceIndexSaveDelayMillis = cacheSourceIndexSaveDelayMillis?.let(::CacheSourceIndexSaveDelayMillis),
        ).takeIfAny(),
        watcher = WatcherConfigOverride(debounceMillis = watcherDebounceMillis?.let(::WatcherDebounceMillis)).takeIfAny(),
        gradle = GradleConfigOverride(
            toolingApiTimeoutMillis = gradleToolingApiTimeoutMillis?.let(::GradleToolingApiTimeoutMillis),
        ).takeIfAny(),
        telemetry = TelemetryConfigOverride(
            enabled = telemetryEnabled?.let(::TelemetryEnabled),
            scopes = telemetryScopes?.takeIf(String::isNotBlank)?.let(::TelemetryScopes),
            detail = telemetryDetail?.takeIf(String::isNotBlank)?.let(::TelemetryDetail),
            outputFile = telemetryOutputFile?.takeIf(String::isNotBlank)?.let { TelemetryOutputFile(OptionalConfigString(it)) },
        ).takeIfAny(),
        backends = BackendsConfigOverride(
            headless = HeadlessBackendConfigOverride(
                enabled = backendsHeadlessEnabled?.let(::HeadlessBackendEnabled),
                runtimeLibsDir = backendsHeadlessRuntimeLibsDir?.takeIf(String::isNotBlank)?.let {
                    HeadlessRuntimeLibsDir(OptionalConfigString(it))
                },
                ideaHome = backendsHeadlessIdeaHome?.takeIf(String::isNotBlank)?.let {
                    HeadlessIdeaHome(OptionalConfigString(it))
                },
            ).takeIfAny(),
            idea = IdeaBackendConfigOverride(enabled = backendsIdeaEnabled?.let(::IdeaBackendEnabled)).takeIfAny(),
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
            phase2PriorityDepth != null ||
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
    takeIf { toolingApiTimeoutMillis != null }

private fun TelemetryConfigOverride.takeIfAny(): TelemetryConfigOverride? =
    takeIf { enabled != null || scopes != null || detail != null || outputFile != null }

private fun BackendsConfigOverride.takeIfAny(): BackendsConfigOverride? =
    takeIf { headless != null || idea != null }

private fun HeadlessBackendConfigOverride.takeIfAny(): HeadlessBackendConfigOverride? =
    takeIf { enabled != null || runtimeLibsDir != null || ideaHome != null }

private fun IdeaBackendConfigOverride.takeIfAny(): IdeaBackendConfigOverride? =
    takeIf { enabled != null }
