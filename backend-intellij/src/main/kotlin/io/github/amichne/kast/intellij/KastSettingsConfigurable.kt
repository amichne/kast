@file:Suppress("UnstableApiUsage")

package io.github.amichne.kast.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceDirectoryResolver
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JTextField

internal class KastSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private var panel: DialogPanel? = null

    private lateinit var serverMaxResults: JBTextField
    private lateinit var serverRequestTimeoutMillis: JBTextField
    private lateinit var serverMaxConcurrentRequests: JBTextField
    private lateinit var indexingPhase2Enabled: JBCheckBox
    private lateinit var indexingPhase2BatchSize: JBTextField
    private lateinit var indexingPhase2PriorityDepth: JBTextField
    private lateinit var indexingIdentifierIndexWaitMillis: JBTextField
    private lateinit var indexingReferenceBatchSize: JBTextField
    private lateinit var indexingRemoteEnabled: JBCheckBox
    private lateinit var indexingRemoteSourceIndexUrl: JBTextField
    private lateinit var cacheEnabled: JBCheckBox
    private lateinit var cacheWriteDelayMillis: JBTextField
    private lateinit var cacheSourceIndexSaveDelayMillis: JBTextField
    private lateinit var watcherDebounceMillis: JBTextField
    private lateinit var gradleToolingApiTimeoutMillis: JBTextField
    private lateinit var telemetryEnabled: JBCheckBox
    private lateinit var telemetryScopes: JBTextField
    private lateinit var telemetryDetail: ComboBox<KastTelemetryDetailLevel>
    private lateinit var telemetryOutputFile: TextFieldWithBrowseButton
    private lateinit var backendsHeadlessEnabled: JBCheckBox
    private lateinit var backendsHeadlessRuntimeLibsDir: TextFieldWithBrowseButton
    private lateinit var backendsHeadlessIdeaHome: TextFieldWithBrowseButton
    private lateinit var backendsIntellijEnabled: JBCheckBox
    private var loadedTelemetryDetailRaw: String? = null

    override fun getDisplayName(): String = "Kast"

    override fun createComponent(): JComponent = ensurePanel()

    override fun isModified(): Boolean {
        ensurePanel()
        val state = KastSettingsState.getInstance(project)
        return serverMaxResults.text != state.serverMaxResults.display() ||
            serverRequestTimeoutMillis.text != state.serverRequestTimeoutMillis.display() ||
            serverMaxConcurrentRequests.text != state.serverMaxConcurrentRequests.display() ||
            indexingPhase2Enabled.isSelected != (state.indexingPhase2Enabled ?: false) ||
            indexingPhase2BatchSize.text != state.indexingPhase2BatchSize.display() ||
            indexingPhase2PriorityDepth.text != state.indexingPhase2PriorityDepth.display() ||
            indexingIdentifierIndexWaitMillis.text != state.indexingIdentifierIndexWaitMillis.display() ||
            indexingReferenceBatchSize.text != state.indexingReferenceBatchSize.display() ||
            indexingRemoteEnabled.isSelected != (state.indexingRemoteEnabled ?: false) ||
            indexingRemoteSourceIndexUrl.text != state.indexingRemoteSourceIndexUrl.orEmpty() ||
            cacheEnabled.isSelected != (state.cacheEnabled ?: false) ||
            cacheWriteDelayMillis.text != state.cacheWriteDelayMillis.display() ||
            cacheSourceIndexSaveDelayMillis.text != state.cacheSourceIndexSaveDelayMillis.display() ||
            watcherDebounceMillis.text != state.watcherDebounceMillis.display() ||
            gradleToolingApiTimeoutMillis.text != state.gradleToolingApiTimeoutMillis.display() ||
            telemetryEnabled.isSelected != (state.telemetryEnabled ?: false) ||
            telemetryScopes.text != state.telemetryScopes.orEmpty() ||
            selectedTelemetryDetailConfigValue(state) != state.telemetryDetail ||
            telemetryOutputFile.text != state.telemetryOutputFile.orEmpty() ||
            backendsHeadlessEnabled.isSelected != (state.backendsHeadlessEnabled ?: false) ||
            backendsHeadlessRuntimeLibsDir.text != state.backendsHeadlessRuntimeLibsDir.orEmpty() ||
            backendsHeadlessIdeaHome.text != state.backendsHeadlessIdeaHome.orEmpty() ||
            backendsIntellijEnabled.isSelected != (state.backendsIntellijEnabled ?: false)
    }

    override fun reset() {
        ensurePanel()
        val workspaceRoot = workspaceRoot()
        val config = workspaceRoot?.let(KastConfig::load) ?: KastConfig.defaults()
        KastSettingsState.getInstance(project).loadFromConfig(config)
        loadFieldsFromState()
    }

    override fun apply() {
        ensurePanel()
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

    override fun disposeUIResources() {
        panel = null
    }

    private fun ensurePanel(): DialogPanel = panel ?: buildPanel().also { panel = it }

    private fun buildPanel(): DialogPanel = panel {
        collapsibleGroup("Server") {
            row("Max results:") {
                serverMaxResults = requiredIntegerTextField("Server max results").component
            }
            row("Request timeout (ms):") {
                serverRequestTimeoutMillis = requiredLongTextField("Server request timeout millis").component
            }
            row("Max concurrent requests:") {
                serverMaxConcurrentRequests = requiredIntegerTextField("Server max concurrent requests").component
            }
        }

        collapsibleGroup("Indexing") {
            row {
                indexingPhase2Enabled = checkBox("Phase 2 enabled").component
            }
            row("Phase 2 batch size:") {
                indexingPhase2BatchSize = requiredIntegerTextField("Indexing phase 2 batch size").component
            }
            row("Phase 2 priority depth:") {
                indexingPhase2PriorityDepth = requiredIntegerTextField("Indexing phase 2 priority depth").component
            }
            row("Identifier index wait (ms):") {
                indexingIdentifierIndexWaitMillis = requiredLongTextField("Identifier index wait millis").component
            }
            row("Reference batch size:") {
                indexingReferenceBatchSize = requiredIntegerTextField("Indexing reference batch size").component
            }
            lateinit var remoteEnabledCell: Cell<JBCheckBox>
            row {
                remoteEnabledCell = checkBox("Remote index enabled").also { indexingRemoteEnabled = it.component }
            }
            row("Remote source index URL:") {
                indexingRemoteSourceIndexUrl = textField()
                    .enabledIf(remoteEnabledCell.selected)
                    .component
            }
        }

        collapsibleGroup("Cache") {
            row {
                cacheEnabled = checkBox("Enabled").component
            }
            row("Write delay (ms):") {
                cacheWriteDelayMillis = requiredLongTextField("Cache write delay millis").component
            }
            row("Source index save delay (ms):") {
                cacheSourceIndexSaveDelayMillis = requiredLongTextField("Cache source index save delay millis").component
            }
        }

        collapsibleGroup("Watcher") {
            row("Debounce (ms):") {
                watcherDebounceMillis = requiredLongTextField("Watcher debounce millis").component
            }
        }

        collapsibleGroup("Gradle") {
            row("Tooling API timeout (ms):") {
                gradleToolingApiTimeoutMillis = requiredLongTextField("Gradle tooling API timeout millis").component
            }
        }

        collapsibleGroup("Telemetry") {
            lateinit var telemetryEnabledCell: Cell<JBCheckBox>
            row {
                telemetryEnabledCell = checkBox("Enabled").also { telemetryEnabled = it.component }
            }
            row("Scopes:") {
                telemetryScopes = textField()
                    .enabledIf(telemetryEnabledCell.selected)
                    .comment("Comma-separated list, or \"all\". Valid scopes: ${canonicalTelemetryScopes()}.")
                    .component
            }
            row("Detail:") {
                telemetryDetail = comboBox(KastTelemetryDetailLevel.entries.toList())
                    .enabledIf(telemetryEnabledCell.selected)
                    .component
            }
            row("Output file:") {
                telemetryOutputFile = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle("Select Telemetry Output File"),
                    project,
                ) { it.path }
                    .enabledIf(telemetryEnabledCell.selected)
                    .component
            }
        }

        collapsibleGroup("Backends") {
            lateinit var headlessEnabledCell: Cell<JBCheckBox>
            row {
                headlessEnabledCell = checkBox("Headless enabled").also { backendsHeadlessEnabled = it.component }
            }
            row("Runtime libs directory:") {
                backendsHeadlessRuntimeLibsDir = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select Runtime Libs Directory"),
                    project,
                ) { it.path }
                    .enabledIf(headlessEnabledCell.selected)
                    .component
            }
            row("IDEA home:") {
                backendsHeadlessIdeaHome = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select IntelliJ IDEA Home"),
                    project,
                ) { it.path }
                    .enabledIf(headlessEnabledCell.selected)
                    .component
            }
            row {
                backendsIntellijEnabled = checkBox("IntelliJ enabled").component
            }
        }
    }

    private fun Row.requiredIntegerTextField(label: String): Cell<JBTextField> =
        textField()
            .validationOnInput { field -> field.integerValidationMessage(label)?.let { error(it) } }
            .validationOnApply { field -> field.integerValidationMessage(label)?.let { error(it) } }

    private fun Row.requiredLongTextField(label: String): Cell<JBTextField> =
        textField()
            .validationOnInput { field -> field.longValidationMessage(label)?.let { error(it) } }
            .validationOnApply { field -> field.longValidationMessage(label)?.let { error(it) } }

    private fun loadFieldsFromState() {
        val state = KastSettingsState.getInstance(project)
        serverMaxResults.text = state.serverMaxResults.display()
        serverRequestTimeoutMillis.text = state.serverRequestTimeoutMillis.display()
        serverMaxConcurrentRequests.text = state.serverMaxConcurrentRequests.display()
        indexingPhase2Enabled.isSelected = state.indexingPhase2Enabled ?: false
        indexingPhase2BatchSize.text = state.indexingPhase2BatchSize.display()
        indexingPhase2PriorityDepth.text = state.indexingPhase2PriorityDepth.display()
        indexingIdentifierIndexWaitMillis.text = state.indexingIdentifierIndexWaitMillis.display()
        indexingReferenceBatchSize.text = state.indexingReferenceBatchSize.display()
        indexingRemoteEnabled.isSelected = state.indexingRemoteEnabled ?: false
        indexingRemoteSourceIndexUrl.text = state.indexingRemoteSourceIndexUrl.orEmpty()
        cacheEnabled.isSelected = state.cacheEnabled ?: false
        cacheWriteDelayMillis.text = state.cacheWriteDelayMillis.display()
        cacheSourceIndexSaveDelayMillis.text = state.cacheSourceIndexSaveDelayMillis.display()
        watcherDebounceMillis.text = state.watcherDebounceMillis.display()
        gradleToolingApiTimeoutMillis.text = state.gradleToolingApiTimeoutMillis.display()
        telemetryEnabled.isSelected = state.telemetryEnabled ?: false
        telemetryScopes.text = state.telemetryScopes.orEmpty()
        loadedTelemetryDetailRaw = state.telemetryDetail
        telemetryDetail.selectedItem = KastTelemetryDetailLevel.fromConfigValue(state.telemetryDetail)
        telemetryOutputFile.text = state.telemetryOutputFile.orEmpty()
        backendsHeadlessEnabled.isSelected = state.backendsHeadlessEnabled ?: false
        backendsHeadlessRuntimeLibsDir.text = state.backendsHeadlessRuntimeLibsDir.orEmpty()
        backendsHeadlessIdeaHome.text = state.backendsHeadlessIdeaHome.orEmpty()
        backendsIntellijEnabled.isSelected = state.backendsIntellijEnabled ?: false
    }

    private fun updateStateFromFields(state: KastSettingsState) {
        state.serverMaxResults = serverMaxResults.readRequiredInt("Server max results")
        state.serverRequestTimeoutMillis = serverRequestTimeoutMillis.readRequiredLong("Server request timeout millis")
        state.serverMaxConcurrentRequests = serverMaxConcurrentRequests.readRequiredInt("Server max concurrent requests")
        state.indexingPhase2Enabled = indexingPhase2Enabled.isSelected
        state.indexingPhase2BatchSize = indexingPhase2BatchSize.readRequiredInt("Indexing phase 2 batch size")
        state.indexingPhase2PriorityDepth = indexingPhase2PriorityDepth.readRequiredInt("Indexing phase 2 priority depth")
        state.indexingIdentifierIndexWaitMillis = indexingIdentifierIndexWaitMillis.readRequiredLong("Identifier index wait millis")
        state.indexingReferenceBatchSize = indexingReferenceBatchSize.readRequiredInt("Indexing reference batch size")
        state.indexingRemoteEnabled = indexingRemoteEnabled.isSelected
        state.indexingRemoteSourceIndexUrl = indexingRemoteSourceIndexUrl.text.takeIf(String::isNotBlank)
        state.cacheEnabled = cacheEnabled.isSelected
        state.cacheWriteDelayMillis = cacheWriteDelayMillis.readRequiredLong("Cache write delay millis")
        state.cacheSourceIndexSaveDelayMillis = cacheSourceIndexSaveDelayMillis.readRequiredLong("Cache source index save delay millis")
        state.watcherDebounceMillis = watcherDebounceMillis.readRequiredLong("Watcher debounce millis")
        state.gradleToolingApiTimeoutMillis = gradleToolingApiTimeoutMillis.readRequiredLong("Gradle tooling API timeout millis")
        state.telemetryEnabled = telemetryEnabled.isSelected
        state.telemetryScopes = telemetryScopes.text.takeIf(String::isNotBlank)
        state.telemetryDetail = selectedTelemetryDetailConfigValue(state)?.takeIf(String::isNotBlank)
        state.telemetryOutputFile = telemetryOutputFile.text.takeIf(String::isNotBlank)
        state.backendsHeadlessEnabled = backendsHeadlessEnabled.isSelected
        state.backendsHeadlessRuntimeLibsDir = backendsHeadlessRuntimeLibsDir.text.takeIf(String::isNotBlank)
        state.backendsHeadlessIdeaHome = backendsHeadlessIdeaHome.text.takeIf(String::isNotBlank)
        state.backendsIntellijEnabled = backendsIntellijEnabled.isSelected
        loadedTelemetryDetailRaw = state.telemetryDetail
    }

    private fun selectedTelemetryDetailConfigValue(state: KastSettingsState): String? {
        val selected = selectedTelemetryDetail()
        return if (state.telemetryDetail == loadedTelemetryDetailRaw &&
            selected == KastTelemetryDetailLevel.fromConfigValue(state.telemetryDetail)
        ) {
            state.telemetryDetail
        } else {
            selected.configValue
        }
    }

    private fun selectedTelemetryDetail(): KastTelemetryDetailLevel =
        telemetryDetail.selectedItem as? KastTelemetryDetailLevel ?: KastTelemetryDetailLevel.BASIC

    private fun workspaceRoot(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
}

private fun Number?.display(): String = this?.toString().orEmpty()

private fun canonicalTelemetryScopes(): String =
    IntelliJTelemetryScope.entries.joinToString(", ") { it.name.lowercase().replace('_', '-') }

private fun JTextField.integerValidationMessage(label: String): String? =
    if (text.toIntOrNull() == null) "$label must be an integer" else null

private fun JTextField.longValidationMessage(label: String): String? =
    if (text.toLongOrNull() == null) "$label must be a long integer" else null

private fun JTextField.readRequiredInt(label: String): Int =
    text.toIntOrNull() ?: throw ConfigurationException("$label must be an integer")

private fun JTextField.readRequiredLong(label: String): Long =
    text.toLongOrNull() ?: throw ConfigurationException("$label must be a long integer")
