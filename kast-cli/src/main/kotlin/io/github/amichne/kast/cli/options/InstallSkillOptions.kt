package io.github.amichne.kast.cli.options

import java.nio.file.Path

internal data class InstallSkillOptions(
    val targetDir: Path?,
    val name: String,
    val force: Boolean,
)
