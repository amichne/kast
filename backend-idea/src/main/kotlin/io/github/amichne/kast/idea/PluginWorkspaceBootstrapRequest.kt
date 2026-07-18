package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.ReleaseRevision
import java.nio.file.Path

data class PluginWorkspaceBootstrapRequest(
    val workspaceRoot: Path,
    val cliBinary: Path,
    val cliVersion: CliImplementationVersion,
    val cliRevision: ReleaseRevision,
    val pluginVersion: PluginVersion,
    val pluginRevision: ReleaseRevision,
) {
    init {
        require(pluginVersion.value != cliVersion.value || pluginRevision == cliRevision) {
            "Same-version plugin and CLI artifacts must share one release revision"
        }
    }
}
