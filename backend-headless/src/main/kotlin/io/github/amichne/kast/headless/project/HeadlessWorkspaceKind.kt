package io.github.amichne.kast.headless

import java.nio.file.Files
import java.nio.file.Path

enum class HeadlessWorkspaceKind {
    GRADLE,
    PLAIN,
    ;

    companion object {
        fun detect(workspaceRoot: Path): HeadlessWorkspaceKind =
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
