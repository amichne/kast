package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationInspection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.distribution.managed.SavedConfigurationIngress
import io.github.amichne.kast.distribution.managed.SavedConfigurationObservation
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class WorkspaceConfigReport(
    val workspace: String,
    val workspaceFile: String,
    val settings: List<ConfigurationInspection>,
    val workspaceAccess: WorkspaceSharingInspection,
)

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
    else {
        output.message("Workspace file: ${report.workspaceFile}")
        output.message(report.workspaceAccess.description())
        for (setting in report.settings) output.message("${setting.key}=${setting.value} (${setting.source})")
        if (report.settings.isEmpty()) output.message("No effective assignment.")
    }
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
    val selected =
        when (action) {
            WorkspaceConfigAction.Show -> settings
            is WorkspaceConfigAction.Explain -> settings.filter { it.key == action.parameter.key }
            is WorkspaceConfigAction.Edit ->
                settings.filter { it.key == ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING.key }
        }
    return WorkspaceConfigReport(
        workspace.path.toString(),
        file.toString(),
        selected,
        inspectWorkspaceSharing(installation, workspace, configuration, environment),
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
    val updated = editSharingAssignment(original, edit)
    val parsed =
        when (val candidate = SavedConfigurationDocument.parse(updated.toByteArray())) {
            is Refinement.Rejected -> throw ManagementRejected("workspace-config", candidate.failure.name)
            is Refinement.Refined -> candidate.value.configurationSources(emptyMap())
        }
    resolveSources(loaded.sources.copy(savedWorkspace = parsed.savedInstallation))
    if (original != updated) WorkspaceOverlayFile.replace(installation, workspace, updated)
}

// UNIX_LINES matches the literal LF separator admitted by SavedConfigurationDocument.
private val sharingRecord =
    Regex(
        "^${ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING.key}=.*(?:\n|\\z)",
        setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES),
    )

private fun editSharingAssignment(original: String, edit: WorkspaceConfigAction.Edit): String {
    val record = sharingRecord.find(original)
    return when (edit) {
        WorkspaceConfigAction.Unset -> if (record == null) original else original.removeRange(record.range)
        is WorkspaceConfigAction.Set -> {
            val assignment = "${ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING.key}=${edit.policy.name}"
            when {
                record != null ->
                    original.replaceRange(record.range, assignment + record.value.takeLastWhile { it == '\n' })
                original.isEmpty() || original.endsWith('\n') -> original + assignment + "\n"
                else -> original + "\n" + assignment + "\n"
            }
        }
    }
}
