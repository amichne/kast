package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import java.nio.file.Path

internal fun installedWorkspaceLifecycleClient(
    userHome: Path,
    selection: ConfigurationPathSelection,
): WorkspaceLifecycleClient =
    when (selection) {
        is ConfigurationPathSelection.Selected -> IdeLifecycleClient(userHome, selection.path)
        ConfigurationPathSelection.OwnerDefault -> WorkspaceLifecycleClient.Unavailable
    }
