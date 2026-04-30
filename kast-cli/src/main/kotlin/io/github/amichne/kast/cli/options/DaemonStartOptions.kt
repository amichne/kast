package io.github.amichne.kast.cli.options

import java.nio.file.Path

internal data class DaemonStartOptions(
    val standaloneArgs: List<String>,
    val workspaceRoot: Path,
    val runtimeLibsDir: Path?,
)
