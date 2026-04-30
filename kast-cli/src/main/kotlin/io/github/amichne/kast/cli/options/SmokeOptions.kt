package io.github.amichne.kast.cli.options

import io.github.amichne.kast.cli.SmokeOutputFormat
import java.nio.file.Path

internal data class SmokeOptions(
    val workspaceRoot: Path,
    val fileFilter: String?,
    val sourceSetFilter: String?,
    val symbolFilter: String?,
    val format: SmokeOutputFormat,
)
