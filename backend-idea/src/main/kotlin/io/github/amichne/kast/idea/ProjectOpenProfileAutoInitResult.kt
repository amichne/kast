package io.github.amichne.kast.idea

import java.nio.file.Path

sealed class ProjectOpenProfileAutoInitResult {
    data class Skipped(val reason: String) : ProjectOpenProfileAutoInitResult()

    data class Installed(
        val metadataPath: Path,
        val backups: List<Path>,
    ) : ProjectOpenProfileAutoInitResult()

    data class Failed(val message: String) : ProjectOpenProfileAutoInitResult()
}
