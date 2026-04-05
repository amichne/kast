package io.github.amichne.kast.cli

import java.nio.file.Path

internal data class InstallOptions(
    val archivePath: Path,
    val instanceName: String?,
    val instancesRoot: Path,
    val binDir: Path,
)
