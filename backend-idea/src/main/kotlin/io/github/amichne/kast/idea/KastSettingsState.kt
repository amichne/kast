package io.github.amichne.kast.idea

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.amichne.kast.api.client.BackendsConfigOverride
import io.github.amichne.kast.api.client.IdeaBackendConfigOverride
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.KastConfigOverride
import io.github.amichne.kast.api.client.ProjectOpenConfigOverride
import io.github.amichne.kast.api.client.RuntimeConfigOverride
import io.github.amichne.kast.api.client.fields.IdeaBackendEnabled
import io.github.amichne.kast.api.client.fields.ProjectOpenAutoExcludeGit
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileAutoInit
import io.github.amichne.kast.api.client.fields.RuntimeDefaultBackend

@State(name = "KastSettings", storages = [Storage("kast.xml")])
@Service(Service.Level.PROJECT)
internal class KastSettingsState : PersistentStateComponent<KastSettingsState> {
    var runtimeDefaultBackend: String? = null
    var cliBinaryPath: String? = null
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
    var projectOpenProfileAutoInit: Boolean? = null
    var projectOpenProfile: String? = null
    var projectOpenAutoExcludeGit: Boolean? = null

    override fun getState(): KastSettingsState = this

    override fun loadState(state: KastSettingsState) = XmlSerializerUtil.copyBean(state, this)

    fun loadFromConfig(config: KastConfig) {
        runtimeDefaultBackend = config.runtime.defaultBackend.value
        backendsIdeaEnabled = config.backends.idea.enabled.value
        cliBinaryPath = config.cli.binaryPath.value
        projectOpenProfileAutoInit = config.projectOpen.profileAutoInit.value
        projectOpenProfile = config.projectOpen.profile.value
        projectOpenAutoExcludeGit = config.projectOpen.autoExcludeGit.value
    }

    fun toOverride(): KastConfigOverride = KastConfigOverride(
        runtime = RuntimeConfigOverride(
            defaultBackend = runtimeDefaultBackend?.takeIf(String::isNotBlank)?.let(::RuntimeDefaultBackend),
        ).takeIfAny(),
        projectOpen = ProjectOpenConfigOverride(
            profileAutoInit = projectOpenProfileAutoInit?.let(::ProjectOpenProfileAutoInit),
            profile = projectOpenProfile?.takeIf(String::isNotBlank)?.let(::ProjectOpenProfile),
            autoExcludeGit = projectOpenAutoExcludeGit?.let(::ProjectOpenAutoExcludeGit),
        ).takeIfAny(),
        backends = BackendsConfigOverride(
            idea = IdeaBackendConfigOverride(enabled = backendsIdeaEnabled?.let(::IdeaBackendEnabled)).takeIfAny(),
        ).takeIfAny(),
    )

    companion object {
        fun getInstance(project: Project): KastSettingsState = project.service()
    }
}

private fun RuntimeConfigOverride.takeIfAny(): RuntimeConfigOverride? =
    takeIf { defaultBackend != null || ideaLaunch != null }

private fun ProjectOpenConfigOverride.takeIfAny(): ProjectOpenConfigOverride? =
    takeIf { profileAutoInit != null || profile != null || autoExcludeGit != null }

private fun BackendsConfigOverride.takeIfAny(): BackendsConfigOverride? =
    takeIf { headless != null || idea != null }

private fun IdeaBackendConfigOverride.takeIfAny(): IdeaBackendConfigOverride? =
    takeIf { enabled != null }
