package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.WorkspaceConfigurationConflict
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

internal fun admitWorkspaceSharing(
    installation: Path,
    workspace: AdmittedWorkspace,
    configuration: ResolvedKastConfiguration,
    environment: Map<String, String>,
): Refinement<WorkspaceDependencySharing, WorkspaceConfigurationConflict.DependencySharing> {
    val policy = configuration.dependencySharingFor(workspace.dependencySharing)
    if (policy != workspace.dependencySharing) {
        val parameter = ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING
        val source = configuration.sourceOf(parameter)
        val location =
            when (source) {
                ConfigurationSource.SAVED_WORKSPACE ->
                    InstalledWorkspaceConfigurationIngress.workspaceFile(installation, workspace.workspace).toString()
                ConfigurationSource.SAVED_INSTALLATION ->
                    environment[ConfigurationParameter.CONFIGURATION_FILE.key]
                        ?: installation.resolve("config/environment").toString()
                else -> source.name
            }
        return Refinement.Rejected(
            WorkspaceConfigurationConflict.DependencySharing(
                workspace.dependencySharing,
                policy,
                source,
                location,
                workspace.workspace.toString(),
            )
        )
    }
    return Refinement.Refined(policy)
}
