package io.github.amichne.kast.api.client

import java.nio.file.Path

object WorkspacePathPolicy {
    private val hardExcludedDirectoryNames = setOf(".gradle", ".idea", ".kotlin", "build", "out")

    fun isHardExcluded(path: Path): Boolean =
        path.any { segment -> segment.toString() in hardExcludedDirectoryNames }
}
