package io.github.amichne.kast.standalone.workspace

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal data class GradleSettingsSnapshot(
    val includedProjectPaths: List<String>,
    val hasCompositeBuilds: Boolean,
) {
    fun shouldPreferStaticDiscovery(maxIncludedProjects: Int = maxIncludedProjectsForToolingApi): Boolean =
        includedProjectPaths.size > maxIncludedProjects

    fun projectPathsForStaticDiscovery(): List<String> = buildList {
        add(":")
        addAll(includedProjectPaths)
    }.distinct()

    companion object {
        private val includeBlockPattern = Regex("""(?s)\binclude\s*\((.*?)\)""")
        private val stringLiteralPattern = Regex("""[\"']([^\"']+)[\"']""")
        private val compositeBuildPattern = Regex("""\bincludeBuild\s*\(""")

        fun read(workspaceRoot: Path): GradleSettingsSnapshot {
            val settingsText = settingsFileCandidates(workspaceRoot)
                .firstOrNull(Path::isRegularFile)
                ?.readText()
                .orEmpty()

            val includedProjectPaths = includeBlockPattern.findAll(settingsText)
                .flatMap { match ->
                    stringLiteralPattern.findAll(match.groupValues[1]).map { literal ->
                        normalizeGradleProjectPath(literal.groupValues[1])
                    }
                }
                .distinct()
                .sorted()
                .toList()

            return GradleSettingsSnapshot(
                includedProjectPaths = includedProjectPaths,
                hasCompositeBuilds = compositeBuildPattern.containsMatchIn(settingsText),
            )
        }

        private fun settingsFileCandidates(workspaceRoot: Path): List<Path> = listOf(
            workspaceRoot.resolve("settings.gradle.kts"),
            workspaceRoot.resolve("settings.gradle"),
        )
    }
}

internal fun normalizeGradleProjectPath(projectPath: String): String = when {
    projectPath == ":" -> ":"
    projectPath.startsWith(":") -> projectPath
    projectPath.isBlank() -> ":"
    else -> ":$projectPath"
}
