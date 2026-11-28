package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationAdmittedRange
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationBoundary
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationInspection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.distribution.contract.shellArgument
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.distribution.managed.SavedConfigurationIngress
import io.github.amichne.kast.distribution.managed.SavedConfigurationObservation
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class WorkspaceConfigReport(
    val workspace: String,
    val workspaceFile: String,
    val settings: List<ConfigurationInspection>,
    val workspaceAccess: WorkspaceSharingInspection,
    /** Present for `explain`, `set`, and `unset`: what the setting means and how to change and apply it. */
    @Required val explanation: SettingExplanation? = null,
)

/** Self-description of one setting, so a caller can change it without reading Kast's source or reference. */
@Serializable
private data class SettingExplanation(
    val key: String,
    val description: String,
    val defaultValue: String?,
    /** What decides the value when nothing sets it, such as an inherited VM option. */
    val defaultAuthority: String,
    val admittedRange: ConfigurationAdmittedRange,
    val sources: List<ConfigurationSource>,
    val applicationBoundary: ConfigurationBoundary,
    /** Null when no source sets the key and the owner decides. */
    @Required val effective: ConfigurationInspection?,
    /** Null when `kast config set` cannot write this key; [sources] then lists where it can be set. */
    @Required val edit: SettingEdit?,
    val apply: String,
)

@Serializable private data class SettingEdit(val accepted: String, val set: String, val unset: String)

internal fun publishWorkspaceConfig(
    root: Path,
    command: ManagementCommand.Config,
    environment: Map<String, String>,
    output: ManagementOutput,
) {
    val workspace =
        when (val discovered = FilesystemCanonicalRootDiscovery.discover(command.workspace)) {
            is CanonicalRootDiscovery.Discovered -> discovered.root
            is CanonicalRootDiscovery.Rejected -> throw ManagementRejected("workspace-config", discovered.failure.name)
        }
    val report =
        when (val action = command.action) {
            is WorkspaceConfigAction.Edit ->
                withWorkspaceEditLock(root) {
                    val installation = selectedInstallation(root).version.path
                    editWorkspaceOverlay(installation, workspace, action, environment)
                    workspaceConfigReport(installation, workspace, action, environment)
                }
            else -> workspaceConfigReport(selectedInstallation(root).version.path, workspace, action, environment)
        }
    if (output.structured(command.json)) output.publish(managementJson.encodeToString(report))
    else report.describe().forEach(output::message)
}

private fun WorkspaceConfigReport.describe(): List<String> = buildList {
    add("Workspace file: $workspaceFile")
    add(workspaceAccess.description())
    when (val explanation = explanation) {
        null -> {
            settings.forEach { add("${it.key}=${it.value} (${it.source})") }
            if (settings.isEmpty()) add("No effective assignment.")
        }
        else -> addAll(explanation.describe())
    }
}

private fun SettingExplanation.describe(): List<String> = buildList {
    add("$key: $description")
    add(
        effective?.let { "Effective: ${it.value} (${it.source})" }
            ?: "Effective: not set; ${defaultValue?.let { "default $it" } ?: defaultAuthority} decides."
    )
    when (val edit = edit) {
        null -> add("Set it in one of: ${sources.joinToString(", ")}. `kast config set` does not edit it.")
        else -> {
            add("Accepts: ${edit.accepted}")
            add("Change: ${edit.set}")
            add("Remove: ${edit.unset}")
        }
    }
    add("Apply: $apply")
}

private fun explanation(
    parameter: ConfigurationParameter,
    workspace: CanonicalRoot,
    effective: ConfigurationInspection?,
): SettingExplanation {
    val declaration = parameter.declaration()
    val root = shellArgument(workspace.path.toString())
    val edit =
        EditableWorkspaceSetting.of(parameter.key)?.let {
            SettingEdit(
                it.accepted,
                "kast config set ${it.key} <value> --workspace $root",
                "kast config unset ${it.key} --workspace $root",
            )
        }
    val apply =
        when (declaration.applicationBoundary) {
            ConfigurationBoundary.NEXT_WORKER_LAUNCH ->
                "A running Host keeps its value. Run `kast stop --workspace $root`; the next semantic request " +
                    "starts the Host with the new value."
            ConfigurationBoundary.NEXT_WORKSPACE_CREATION ->
                "Applies when Kast creates this workspace. workspaceAccess shows the policy an existing workspace keeps."
            ConfigurationBoundary.NEXT_CONNECTION -> "Applies to the next agent or client connection."
            ConfigurationBoundary.NEXT_INSTALLATION_ACTIVATION -> "Applies when the installation is next activated."
            ConfigurationBoundary.REQUEST -> "Applies to each request that carries it."
            ConfigurationBoundary.BUILD,
            ConfigurationBoundary.TEST -> "Applies to Kast's own build and tests, not to an installed product."
        }
    return SettingExplanation(
        declaration.key,
        declaration.description,
        declaration.defaultValue,
        declaration.defaultAuthority,
        declaration.admittedRange,
        declaration.sources,
        declaration.applicationBoundary,
        effective,
        edit,
        apply,
    )
}

private fun workspaceConfigReport(
    installation: Path,
    workspace: CanonicalRoot,
    action: WorkspaceConfigAction,
    environment: Map<String, String>,
): WorkspaceConfigReport {
    val file = InstalledWorkspaceConfigurationIngress.workspaceFile(installation, workspace.path)
    val configuration = resolveSources(workspaceSources(installation, workspace, environment).sources)
    val settings = configuration.inspection()
    val parameter =
        when (action) {
            WorkspaceConfigAction.Show -> null
            is WorkspaceConfigAction.Explain -> action.parameter
            is WorkspaceConfigAction.Edit -> action.setting.parameter
        }
    val selected = if (parameter == null) settings else settings.filter { it.key == parameter.key }
    return WorkspaceConfigReport(
        workspace.path.toString(),
        file.toString(),
        selected,
        inspectWorkspaceSharing(installation, workspace, configuration, environment),
        parameter?.let { explanation(it, workspace, selected.singleOrNull()) },
    )
}

private fun workspaceSources(installation: Path, workspace: CanonicalRoot, environment: Map<String, String>) =
    when (val read = InstalledWorkspaceConfigurationIngress.load(installation, workspace.path, environment)) {
        is SavedConfigurationIngress.Loaded -> read
        is SavedConfigurationIngress.Rejected -> throw ManagementRejected("workspace-config", read.rejection.reason())
    }

private fun resolveSources(sources: ConfigurationSources): ResolvedKastConfiguration =
    when (val result = ResolvedKastConfiguration.resolve(sources)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected ->
            throw ManagementRejected("workspace-config", "${result.failure.key}: ${result.failure.reason}")
    }

/** The installation locks cover the read, replacement, and effective-value report. */
private fun editWorkspaceOverlay(
    installation: Path,
    workspace: CanonicalRoot,
    edit: WorkspaceConfigAction.Edit,
    environment: Map<String, String>,
) {
    val loaded = workspaceSources(installation, workspace, environment)
    val file = InstalledWorkspaceConfigurationIngress.workspaceFile(installation, workspace.path)
    val original =
        when (loaded.workspaceObservation) {
            SavedConfigurationObservation.ABSENT -> ""
            SavedConfigurationObservation.LOADED ->
                readBoundedFile(file, SavedConfigurationDocument.MAXIMUM_BYTES.toLong())
                    ?: throw ManagementRejected("workspace-config", "UNREADABLE_OVERLAY")
        }
    val updated = editAssignment(original, edit)
    val parsed =
        when (val candidate = SavedConfigurationDocument.parse(updated.toByteArray())) {
            is Refinement.Rejected -> throw ManagementRejected("workspace-config", candidate.failure.name)
            is Refinement.Refined -> candidate.value.configurationSources(emptyMap())
        }
    resolveSources(loaded.sources.copy(savedWorkspace = parsed.savedInstallation))
    if (original != updated) WorkspaceOverlayFile.replace(installation, workspace, updated)
}

// UNIX_LINES matches the literal LF separator admitted by SavedConfigurationDocument. Keys are fixed identifiers.
private fun savedRecord(setting: EditableWorkspaceSetting): Regex =
    Regex("^${Regex.escape(setting.key)}=.*(?:\n|\\z)", setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES))

/** Replaces or removes one setting's line and keeps every other line, comment, and order. */
private fun editAssignment(original: String, edit: WorkspaceConfigAction.Edit): String {
    val record = savedRecord(edit.setting).find(original)
    return when (edit) {
        is WorkspaceConfigAction.Unset -> if (record == null) original else original.removeRange(record.range)
        is WorkspaceConfigAction.Set -> {
            val assignment = edit.assignment.line
            when {
                record != null ->
                    original.replaceRange(record.range, assignment + record.value.takeLastWhile { it == '\n' })
                original.isEmpty() || original.endsWith('\n') -> original + assignment + "\n"
                else -> original + "\n" + assignment + "\n"
            }
        }
    }
}
