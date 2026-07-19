package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import java.nio.file.Path

data class PluginWorkspaceBootstrapRequest(
    val workspaceRoot: Path,
    val cliBinary: Path,
    val cliVersion: CliImplementationVersion,
    val pluginVersion: PluginVersion,
)
