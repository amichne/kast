package io.github.amichne.kast.indexer.project

import java.nio.file.Files
import java.nio.file.Path

enum class WorkspaceKind {
    GRADLE,
    PLAIN,
    ;

    companion object {
        fun detect(workspaceRoot: Path): WorkspaceKind =
            if (GRADLE_MARKERS.any { marker -> Files.isRegularFile(workspaceRoot.resolve(marker)) }) {
                GRADLE
            } else {
                PLAIN
            }

        private val GRADLE_MARKERS = listOf(
            "settings.gradle.kts",
            "settings.gradle",
            "build.gradle.kts",
            "build.gradle",
        )
    }
}
