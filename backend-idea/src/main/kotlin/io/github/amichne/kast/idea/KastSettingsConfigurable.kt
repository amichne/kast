@file:Suppress("UnstableApiUsage")

package io.github.amichne.kast.idea

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceDirectoryResolver
import io.github.amichne.kast.api.client.kastConfigHome
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

internal class KastSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private var panel: DialogPanel? = null

    private lateinit var runtimeDefaultBackend: ComboBox<KastRuntimeDefaultBackendOption>
    private lateinit var runtimeStrictPluginMatching: JBCheckBox
    private lateinit var backendsIdeaEnabled: JBCheckBox
    private lateinit var projectOpenProfileAutoInit: JBCheckBox
    private lateinit var projectOpenAutoExcludeGit: JBCheckBox
    private lateinit var projectOpenGradleLoadEnabled: JBCheckBox
    private lateinit var codexHooksEnabled: JBCheckBox
    private lateinit var codexSessionStartEnabled: JBCheckBox
    private lateinit var codexPostToolUseEnabled: JBCheckBox

    override fun getDisplayName(): String = "Kast"

    override fun createComponent(): JComponent = ensurePanel()

    override fun isModified(): Boolean {
        ensurePanel()
        val state = KastSettingsState.getInstance(project)
        return selectedRuntimeDefaultBackend().configValue != state.runtimeDefaultBackend ||
            runtimeStrictPluginMatching.isSelected != (state.runtimeStrictPluginMatching ?: true) ||
            backendsIdeaEnabled.isSelected != (state.backendsIdeaEnabled ?: false) ||
            projectOpenProfileAutoInit.isSelected != (state.projectOpenProfileAutoInit ?: false) ||
            projectOpenAutoExcludeGit.isSelected != (state.projectOpenAutoExcludeGit ?: true) ||
            projectOpenGradleLoadEnabled.isSelected != (state.projectOpenGradleLoadEnabled ?: true) ||
            codexHooksEnabled.isSelected != (state.codexHooksEnabled ?: true) ||
            codexSessionStartEnabled.isSelected != (state.codexSessionStartEnabled ?: true) ||
            codexPostToolUseEnabled.isSelected != (state.codexPostToolUseEnabled ?: true)
    }

    override fun reset() {
        ensurePanel()
        val workspaceRoot = workspaceRoot()
        val config = workspaceRoot?.let(KastConfig::loadIdea) ?: KastConfig.defaults()
        KastSettingsState.getInstance(project).loadFromConfig(config)
        loadFieldsFromState()
    }

    override fun apply() {
        ensurePanel()
        val workspaceRoot = workspaceRoot() ?: return
        val state = KastSettingsState.getInstance(project)
        updateStateFromFields(state)

        val configPath = workspaceConfigPath(workspaceRoot)
        val existingToml = if (Files.isRegularFile(configPath)) Files.readString(configPath) else ""
        val nextToml = mergePublicWorkspaceToml(existingToml, state)
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, nextToml)

        val globalConfigPath = kastConfigHome().resolve("config.toml")
        val existingGlobalToml = if (Files.isRegularFile(globalConfigPath)) Files.readString(globalConfigPath) else ""
        Files.createDirectories(globalConfigPath.parent)
        Files.writeString(globalConfigPath, mergeGlobalCodexHooksToml(existingGlobalToml, state))

        KastPluginService.getInstance(project).reloadConfig()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun ensurePanel(): DialogPanel = panel ?: buildPanel().also { panel = it }

    private fun buildPanel(): DialogPanel = panel {
        group("Runtime") {
            row("Default backend:") {
                runtimeDefaultBackend = comboBox(KastRuntimeDefaultBackendOption.entries.toList()).component
            }
            row {
                runtimeStrictPluginMatching = checkBox("Require matching Kast plugin version").component
            }
            row {
                backendsIdeaEnabled = checkBox("IDEA backend enabled").component
            }
        }

        group("Project Open") {
            row {
                projectOpenProfileAutoInit = checkBox("Prepare Kast workspaces when Gradle projects open").component
            }
            row {
                projectOpenGradleLoadEnabled = checkBox("Load Gradle project model on open").component
            }
            row {
                projectOpenAutoExcludeGit = checkBox("Exclude managed setup files from Git").component
            }
        }

        group("Codex Hooks (Global)") {
            row {
                codexHooksEnabled = checkBox("Enable Codex hooks").component
            }
            row {
                codexSessionStartEnabled = checkBox("Open worktrees on session start").component
            }
            row {
                codexPostToolUseEnabled = checkBox("Diagnose Kotlin files after writes").component
            }
        }

        group("Configuration") {
            row {
                button("Open workspace config") { openWorkspaceConfig() }
            }
        }
    }

    private fun loadFieldsFromState() {
        val state = KastSettingsState.getInstance(project)
        runtimeDefaultBackend.selectedItem =
            KastRuntimeDefaultBackendOption.fromConfigValue(state.runtimeDefaultBackend)
        runtimeStrictPluginMatching.isSelected = state.runtimeStrictPluginMatching ?: true
        backendsIdeaEnabled.isSelected = state.backendsIdeaEnabled ?: false
        projectOpenProfileAutoInit.isSelected = state.projectOpenProfileAutoInit ?: false
        projectOpenAutoExcludeGit.isSelected = state.projectOpenAutoExcludeGit ?: true
        projectOpenGradleLoadEnabled.isSelected = state.projectOpenGradleLoadEnabled ?: true
        codexHooksEnabled.isSelected = state.codexHooksEnabled ?: true
        codexSessionStartEnabled.isSelected = state.codexSessionStartEnabled ?: true
        codexPostToolUseEnabled.isSelected = state.codexPostToolUseEnabled ?: true
    }

    private fun updateStateFromFields(state: KastSettingsState) {
        state.runtimeDefaultBackend = selectedRuntimeDefaultBackend().configValue
        state.runtimeStrictPluginMatching = runtimeStrictPluginMatching.isSelected
        state.backendsIdeaEnabled = backendsIdeaEnabled.isSelected
        state.projectOpenProfileAutoInit = projectOpenProfileAutoInit.isSelected
        state.projectOpenProfile = io.github.amichne.kast.api.client.fields.ProjectOpenProfile.JETBRAINS_PLUGIN
        state.projectOpenAutoExcludeGit = projectOpenAutoExcludeGit.isSelected
        state.projectOpenGradleLoadEnabled = projectOpenGradleLoadEnabled.isSelected
        state.codexHooksEnabled = codexHooksEnabled.isSelected
        state.codexSessionStartEnabled = codexSessionStartEnabled.isSelected
        state.codexPostToolUseEnabled = codexPostToolUseEnabled.isSelected
    }

    private fun selectedRuntimeDefaultBackend(): KastRuntimeDefaultBackendOption =
        runtimeDefaultBackend.selectedItem as? KastRuntimeDefaultBackendOption
            ?: KastRuntimeDefaultBackendOption.AUTO

    private fun openWorkspaceConfig() {
        val workspaceRoot = workspaceRoot() ?: return
        val configPath = workspaceConfigPath(workspaceRoot)
        Files.createDirectories(configPath.parent)
        if (Files.notExists(configPath)) {
            Files.writeString(configPath, "")
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configPath) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun workspaceRoot(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }

    private fun workspaceConfigPath(workspaceRoot: Path): Path = WorkspaceDirectoryResolver()
        .workspaceDataDirectory(workspaceRoot)
        .resolve("config.toml")
}

private enum class KastRuntimeDefaultBackendOption(
    val configValue: String,
    private val label: String,
) {
    AUTO("auto", "Automatic"),
    HEADLESS("headless", "Headless"),
    IDEA("idea", "IDEA");

    override fun toString(): String = label

    companion object {
        fun fromConfigValue(value: String?): KastRuntimeDefaultBackendOption =
            entries.firstOrNull { it.configValue == value } ?: AUTO
    }
}
