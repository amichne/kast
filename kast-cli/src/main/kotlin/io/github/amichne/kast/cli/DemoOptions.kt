package io.github.amichne.kast.cli

import java.nio.file.Path

internal data class DemoOptions(
    val workspaceRoot: Path,
    val symbolFilter: String?,
)
