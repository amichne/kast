package io.github.amichne.kast.cli

import java.nio.file.Path

internal data class DaemonStartOptions(
    val standaloneArgs: List<String>,
    val runtimeLibsDir: Path?,
)
