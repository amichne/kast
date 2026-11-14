package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.IdeLifecycleDeployment
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import java.nio.file.Path

internal fun installedWorkspaceLifecycleClient(
    userHome: Path,
    configuration: ResolvedKastConfiguration,
): WorkspaceLifecycleClient =
    when (val selection = configuration.selectedIdeHome) {
        is ConfigurationPathSelection.Selected ->
            when (val managedHeadlessState = configuration.managedHeadlessState) {
                ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
                is ConfigurationPathSelection.Selected ->
                    IdeLifecycleClient(
                        userHome,
                        selection.path,
                        deployment =
                            IdeLifecycleDeployment.Headless(
                                managedHeadlessState.path,
                                configuration.hostIdleShutdown,
                            ),
                    )
            }
        ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
    }
