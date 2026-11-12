package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.IdeLifecycleDeployment
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import java.nio.file.Path

internal fun installedWorkspaceLifecycleClient(
    userHome: Path,
    selection: ConfigurationPathSelection,
    managedHeadlessState: ConfigurationPathSelection = ConfigurationPathSelection.OwnerDefault,
): WorkspaceLifecycleClient =
    when (selection) {
        is ConfigurationPathSelection.Selected ->
            when (managedHeadlessState) {
                ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
                is ConfigurationPathSelection.Selected ->
                    IdeLifecycleClient(
                        userHome,
                        selection.path,
                        deployment = IdeLifecycleDeployment.Headless(managedHeadlessState.path),
                    )
            }
        ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
    }
