package io.github.amichne.kast.idea

import java.nio.file.Path

sealed class PluginWorkspaceBootstrapResult {
    data class Prepared(
        val metadataPath: Path,
        val backups: List<Path>,
    ) : PluginWorkspaceBootstrapResult()

    data class Rejected(val message: String) : PluginWorkspaceBootstrapResult()
}
