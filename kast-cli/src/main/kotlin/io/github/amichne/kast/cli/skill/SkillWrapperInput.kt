package io.github.amichne.kast.cli.skill

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Shared input handling for skill wrapper commands:
 * JSON parsing (literal vs file path) and workspace root resolution.
 */
internal object SkillWrapperInput {

    /**
     * Resolves the workspace root from an explicit request value or the current working directory.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveWorkspaceRoot(
        explicit: String?,
        env: Map<String, String> = emptyMap(),
        currentWorkingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    ): String {
        val trimmed = explicit?.trim()?.takeIf(String::isNotEmpty)
        if (trimmed != null) return Path.of(trimmed).toAbsolutePath().normalize().toString()
        return currentWorkingDirectory.toString()
    }

    /**
     * Reads the raw JSON input: if it looks like JSON (starts with `{` or `[`),
     * returns it directly. Otherwise treats it as a file path and reads the content.
     */
    fun parseJsonInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        val path = Path.of(trimmed)
        if (path.exists()) {
            return path.readText().trim()
        }
        throw IllegalArgumentException(
            "Skill wrapper input must be a JSON literal or a path to a JSON file: $input",
        )
    }
}
