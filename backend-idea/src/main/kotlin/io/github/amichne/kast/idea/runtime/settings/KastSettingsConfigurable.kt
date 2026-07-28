@file:Suppress("UnstableApiUsage")

package io.github.amichne.kast.idea

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import java.nio.file.StandardCopyOption
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
        val nextState = KastSettingsState()
        updateStateFromFields(nextState)

        val configPath = workspaceConfigPath(workspaceRoot)
        val globalConfigPath = kastConfigHome().resolve("config.toml")
        val configUpdates = listOf(
            configPath to mergePublicWorkspaceToml(readConfigText(configPath), nextState),
            globalConfigPath to mergeGlobalCodexHooksToml(readConfigText(globalConfigPath), nextState),
        )
        try {
            writeConfigFilesTransactionally(configUpdates)
            configUpdates.forEach { (path, contents) -> updateCachedDocument(path, contents) }
        } catch (error: Exception) {
            throw ConfigurationException(
                "Could not save Kast settings: ${error.message ?: error::class.java.simpleName}",
            ).also { failure -> failure.initCause(error) }
        }

        state.loadState(nextState)
        KastPluginService.getInstance(project).reloadConfigAsync()
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
            writeTextAtomically(configPath, "")
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configPath) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun workspaceRoot(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }

    private fun workspaceConfigPath(workspaceRoot: Path): Path = WorkspaceDirectoryResolver()
        .workspaceDataDirectory(workspaceRoot)
        .resolve("config.toml")

    private fun readConfigText(path: Path): String {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        val document = file?.let(FileDocumentManager.getInstance()::getCachedDocument)
        return document?.text ?: if (Files.isRegularFile(path)) Files.readString(path) else ""
    }

    private fun updateCachedDocument(path: Path, contents: String) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        val documentManager = FileDocumentManager.getInstance()
        val document = file?.let(documentManager::getCachedDocument)
        if (document != null) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(contents)
            }
            documentManager.saveDocument(document)
        }
    }
}

internal fun writeConfigFilesTransactionally(
    updates: List<Pair<Path, String>>,
    writer: (Path, String) -> Unit = ::writeTextAtomically,
) {
    val originals = updates.associate { (path, _) ->
        path to if (Files.isRegularFile(path)) Files.readString(path) else null
    }
    val committed = mutableListOf<Path>()
    try {
        updates.forEach { (path, contents) ->
            writer(path, contents)
            committed.add(path)
        }
    } catch (failure: Exception) {
        committed.asReversed().forEach { path ->
            runCatching {
                originals.getValue(path)?.let { original -> writer(path, original) }
                    ?: Files.deleteIfExists(path)
            }.onFailure(failure::addSuppressed)
        }
        throw failure
    }
}

internal fun writeTextAtomically(target: Path, contents: String) {
    Files.createDirectories(target.parent)
    val staging = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
    try {
        Files.writeString(staging, contents)
        runCatching {
            Files.move(
                staging,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is java.nio.file.AtomicMoveNotSupportedException) throw error
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    } finally {
        Files.deleteIfExists(staging)
    }
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
