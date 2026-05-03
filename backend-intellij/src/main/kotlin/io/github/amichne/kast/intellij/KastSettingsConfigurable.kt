package io.github.amichne.kast.intellij

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceDirectoryResolver
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.TitledBorder

internal class KastSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private var panel: JPanel? = null

    private val serverMaxResults = JTextField()
    private val serverRequestTimeoutMillis = JTextField()
    private val serverMaxConcurrentRequests = JTextField()
    private val indexingPhase2Enabled = JCheckBox()
    private val indexingPhase2BatchSize = JTextField()
    private val indexingIdentifierIndexWaitMillis = JTextField()
    private val indexingReferenceBatchSize = JTextField()
    private val indexingRemoteEnabled = JCheckBox()
    private val indexingRemoteSourceIndexUrl = JTextField()
    private val cacheEnabled = JCheckBox()
    private val cacheWriteDelayMillis = JTextField()
    private val cacheSourceIndexSaveDelayMillis = JTextField()
    private val watcherDebounceMillis = JTextField()
    private val gradleToolingApiTimeoutMillis = JTextField()
    private val gradleMaxIncludedProjects = JTextField()
    private val telemetryEnabled = JCheckBox()
    private val telemetryScopes = JTextField()
    private val telemetryDetail = JTextField()
    private val telemetryOutputFile = JTextField()
    private val backendsStandaloneEnabled = JCheckBox()
    private val backendsStandaloneRuntimeLibsDir = JTextField()
    private val backendsIntellijEnabled = JCheckBox()

    override fun getDisplayName(): String = "Kast"

    override fun createComponent(): JComponent = panel ?: buildPanel().also { panel = it }

    override fun isModified(): Boolean {
        val state = KastSettingsState.getInstance(project)
        return serverMaxResults.text != state.serverMaxResults.display() ||
            serverRequestTimeoutMillis.text != state.serverRequestTimeoutMillis.display() ||
            serverMaxConcurrentRequests.text != state.serverMaxConcurrentRequests.display() ||
            indexingPhase2Enabled.isSelected != (state.indexingPhase2Enabled ?: false) ||
            indexingPhase2BatchSize.text != state.indexingPhase2BatchSize.display() ||
            indexingIdentifierIndexWaitMillis.text != state.indexingIdentifierIndexWaitMillis.display() ||
            indexingReferenceBatchSize.text != state.indexingReferenceBatchSize.display() ||
            indexingRemoteEnabled.isSelected != (state.indexingRemoteEnabled ?: false) ||
            indexingRemoteSourceIndexUrl.text != state.indexingRemoteSourceIndexUrl.orEmpty() ||
            cacheEnabled.isSelected != (state.cacheEnabled ?: false) ||
            cacheWriteDelayMillis.text != state.cacheWriteDelayMillis.display() ||
            cacheSourceIndexSaveDelayMillis.text != state.cacheSourceIndexSaveDelayMillis.display() ||
            watcherDebounceMillis.text != state.watcherDebounceMillis.display() ||
            gradleToolingApiTimeoutMillis.text != state.gradleToolingApiTimeoutMillis.display() ||
            gradleMaxIncludedProjects.text != state.gradleMaxIncludedProjects.display() ||
            telemetryEnabled.isSelected != (state.telemetryEnabled ?: false) ||
            telemetryScopes.text != state.telemetryScopes.orEmpty() ||
            telemetryDetail.text != state.telemetryDetail.orEmpty() ||
            telemetryOutputFile.text != state.telemetryOutputFile.orEmpty() ||
            backendsStandaloneEnabled.isSelected != (state.backendsStandaloneEnabled ?: false) ||
            backendsStandaloneRuntimeLibsDir.text != state.backendsStandaloneRuntimeLibsDir.orEmpty() ||
            backendsIntellijEnabled.isSelected != (state.backendsIntellijEnabled ?: false)
    }

    override fun reset() {
        val workspaceRoot = workspaceRoot()
        val config = workspaceRoot?.let(KastConfig::load) ?: KastConfig.defaults()
        KastSettingsState.getInstance(project).loadFromConfig(config)
        loadFieldsFromState()
    }

    override fun apply() {
        val workspaceRoot = workspaceRoot() ?: return
        val state = KastSettingsState.getInstance(project)
        val previousServer = state.toOverride().server
        val previousBackends = state.toOverride().backends
        updateStateFromFields(state)

        val configPath = WorkspaceDirectoryResolver()
            .workspaceDataDirectory(workspaceRoot)
            .resolve("config.toml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, state.toWorkspaceToml())

        val nextOverride = state.toOverride()
        if (previousServer != nextOverride.server || previousBackends != nextOverride.backends) {
            KastPluginService.getInstance(project).restartServer()
        }
    }

    private fun buildPanel(): JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(section("Server") {
            row("Max results", serverMaxResults)
            row("Request timeout millis", serverRequestTimeoutMillis)
            row("Max concurrent requests", serverMaxConcurrentRequests)
        })
        add(section("Indexing") {
            row("Phase 2 enabled", indexingPhase2Enabled)
            row("Phase 2 batch size", indexingPhase2BatchSize)
            row("Identifier index wait millis", indexingIdentifierIndexWaitMillis)
            row("Reference batch size", indexingReferenceBatchSize)
            row("Remote index enabled", indexingRemoteEnabled)
            row("Remote source index URL", indexingRemoteSourceIndexUrl)
        })
        add(section("Cache") {
            row("Enabled", cacheEnabled)
            row("Write delay millis", cacheWriteDelayMillis)
            row("Source index save delay millis", cacheSourceIndexSaveDelayMillis)
        })
        add(section("Watcher") { row("Debounce millis", watcherDebounceMillis) })
        add(section("Gradle") {
            row("Tooling API timeout millis", gradleToolingApiTimeoutMillis)
            row("Max included projects", gradleMaxIncludedProjects)
        })
        add(section("Telemetry") {
            row("Enabled", telemetryEnabled)
            row("Scopes", telemetryScopes)
            row("Detail", telemetryDetail)
            row("Output file", telemetryOutputFile)
        })
        add(section("Backends") {
            row("Standalone enabled", backendsStandaloneEnabled)
            row("Standalone runtime libs dir", backendsStandaloneRuntimeLibsDir)
            row("IntelliJ enabled", backendsIntellijEnabled)
        })
    }

    private fun loadFieldsFromState() {
        val state = KastSettingsState.getInstance(project)
        serverMaxResults.text = state.serverMaxResults.display()
        serverRequestTimeoutMillis.text = state.serverRequestTimeoutMillis.display()
        serverMaxConcurrentRequests.text = state.serverMaxConcurrentRequests.display()
        indexingPhase2Enabled.isSelected = state.indexingPhase2Enabled ?: false
        indexingPhase2BatchSize.text = state.indexingPhase2BatchSize.display()
        indexingIdentifierIndexWaitMillis.text = state.indexingIdentifierIndexWaitMillis.display()
        indexingReferenceBatchSize.text = state.indexingReferenceBatchSize.display()
        indexingRemoteEnabled.isSelected = state.indexingRemoteEnabled ?: false
        indexingRemoteSourceIndexUrl.text = state.indexingRemoteSourceIndexUrl.orEmpty()
        cacheEnabled.isSelected = state.cacheEnabled ?: false
        cacheWriteDelayMillis.text = state.cacheWriteDelayMillis.display()
        cacheSourceIndexSaveDelayMillis.text = state.cacheSourceIndexSaveDelayMillis.display()
        watcherDebounceMillis.text = state.watcherDebounceMillis.display()
        gradleToolingApiTimeoutMillis.text = state.gradleToolingApiTimeoutMillis.display()
        gradleMaxIncludedProjects.text = state.gradleMaxIncludedProjects.display()
        telemetryEnabled.isSelected = state.telemetryEnabled ?: false
        telemetryScopes.text = state.telemetryScopes.orEmpty()
        telemetryDetail.text = state.telemetryDetail.orEmpty()
        telemetryOutputFile.text = state.telemetryOutputFile.orEmpty()
        backendsStandaloneEnabled.isSelected = state.backendsStandaloneEnabled ?: false
        backendsStandaloneRuntimeLibsDir.text = state.backendsStandaloneRuntimeLibsDir.orEmpty()
        backendsIntellijEnabled.isSelected = state.backendsIntellijEnabled ?: false
    }

    private fun updateStateFromFields(state: KastSettingsState) {
        state.serverMaxResults = serverMaxResults.parseInt("Server max results")
        state.serverRequestTimeoutMillis = serverRequestTimeoutMillis.parseLong("Server request timeout millis")
        state.serverMaxConcurrentRequests = serverMaxConcurrentRequests.parseInt("Server max concurrent requests")
        state.indexingPhase2Enabled = indexingPhase2Enabled.isSelected
        state.indexingPhase2BatchSize = indexingPhase2BatchSize.parseInt("Indexing phase 2 batch size")
        state.indexingIdentifierIndexWaitMillis = indexingIdentifierIndexWaitMillis.parseLong("Identifier index wait millis")
        state.indexingReferenceBatchSize = indexingReferenceBatchSize.parseInt("Indexing reference batch size")
        state.indexingRemoteEnabled = indexingRemoteEnabled.isSelected
        state.indexingRemoteSourceIndexUrl = indexingRemoteSourceIndexUrl.text.takeIf(String::isNotBlank)
        state.cacheEnabled = cacheEnabled.isSelected
        state.cacheWriteDelayMillis = cacheWriteDelayMillis.parseLong("Cache write delay millis")
        state.cacheSourceIndexSaveDelayMillis = cacheSourceIndexSaveDelayMillis.parseLong("Cache source index save delay millis")
        state.watcherDebounceMillis = watcherDebounceMillis.parseLong("Watcher debounce millis")
        state.gradleToolingApiTimeoutMillis = gradleToolingApiTimeoutMillis.parseLong("Gradle tooling API timeout millis")
        state.gradleMaxIncludedProjects = gradleMaxIncludedProjects.parseInt("Gradle max included projects")
        state.telemetryEnabled = telemetryEnabled.isSelected
        state.telemetryScopes = telemetryScopes.text.takeIf(String::isNotBlank)
        state.telemetryDetail = telemetryDetail.text.takeIf(String::isNotBlank)
        state.telemetryOutputFile = telemetryOutputFile.text.takeIf(String::isNotBlank)
        state.backendsStandaloneEnabled = backendsStandaloneEnabled.isSelected
        state.backendsStandaloneRuntimeLibsDir = backendsStandaloneRuntimeLibsDir.text.takeIf(String::isNotBlank)
        state.backendsIntellijEnabled = backendsIntellijEnabled.isSelected
    }

    private fun workspaceRoot(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
}

private fun section(
    title: String,
    body: JPanel.() -> Unit,
): JPanel = JPanel().apply {
    layout = java.awt.GridLayout(0, 2, 8, 4)
    border = TitledBorder(title)
    body()
}

private fun JPanel.row(
    label: String,
    component: JComponent,
) {
    add(JLabel(label))
    add(component)
}

private fun Number?.display(): String = this?.toString().orEmpty()

private fun JTextField.parseInt(label: String): Int? =
    text.takeIf(String::isNotBlank)?.toIntOrNull()
        ?: throw ConfigurationException("$label must be an integer")

private fun JTextField.parseLong(label: String): Long? =
    text.takeIf(String::isNotBlank)?.toLongOrNull()
        ?: throw ConfigurationException("$label must be a long integer")
