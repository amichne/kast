package io.github.amichne.kast.cli.options

import java.nio.file.Path

internal data class InstallCopilotExtensionOptions(
    val targetDir: Path?,
    val force: Boolean,
)
