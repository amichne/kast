package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.HostChildRetention
import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.IdeLifecycleDeployment
import io.github.amichne.kast.appserver.ide.LifecycleExchangeTimer
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import java.nio.file.Path

internal fun installedWorkspaceLifecycleClient(
    userHome: Path,
    configuration: ResolvedKastConfiguration,
    children: HostChildRetention = HostChildRetention.Discarded,
): WorkspaceLifecycleClient =
    when (val selection = configuration.selectedIdeHome) {
        is ConfigurationPathSelection.Selected ->
            when (val managedHeadlessState = configuration.managedHeadlessState) {
                ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
                is ConfigurationPathSelection.Selected ->
                    IdeLifecycleClient(
                        userHome,
                        selection.path,
                        exchangeTimer = LifecycleExchangeTimer.Native,
                        nanoTime = System::nanoTime,
                        children = children,
                        deployment =
                            IdeLifecycleDeployment.Headless(
                                managedHeadlessState.path,
                                configuration.hostIdleShutdown,
                                configuration.hostMaxHeap,
                                configuration.gradleImportEnvironment,
                            ),
                    )
            }
        ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
    }
