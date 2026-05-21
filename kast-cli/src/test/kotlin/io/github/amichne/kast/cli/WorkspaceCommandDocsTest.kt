package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class WorkspaceCommandDocsTest {
    @Test
    fun `repo docs do not advertise removed direct cli commands`() {
        val repoRoot = locateRepoRoot()
        val files = buildList {
            add(repoRoot.resolve("README.md"))
            addAll(markdownFilesUnder(repoRoot.resolve("docs")))
            addAll(
                listOf(
                    repoRoot.resolve("kast.sh"),
                    repoRoot.resolve(".agents/skills/kast/SKILL.md"),
                ),
            )
        }
        val removedCommandSnippets = listOf(
            "kast workspace ",
            "kast self ",
            "kast install copilot-extension --uninstall=true",
            "kast resolve",
            "kast references",
            "kast call-hierarchy",
            "kast type-hierarchy",
            "kast implementations",
            "kast outline",
            "kast workspace-symbol",
            "kast workspace-search",
            "kast insertion-point",
            "kast diagnostics",
            "kast rename",
            "kast optimize-imports",
            "kast apply-edits",
            "kast code-actions",
            "kast completions",
            "kast health",
            "`workspace ensure`",
            "`workspace status`",
            "`workspace stop`",
            "`workspace refresh`",
            "`workspace files`",
            "`self status`",
            "`self doctor`",
            "`self uninstall`",
            "`self upgrade`",
        )
        val violations = files.flatMap { path ->
            val text = path.readText()
            removedCommandSnippets
                .filter(text::contains)
                .map { snippet -> "$path contains $snippet" }
        }

        assertTrue(
            violations.isEmpty(),
            "Removed CLI command snippets still documented:\n${violations.joinToString("\n")}",
        )
    }

    private fun markdownFilesUnder(root: Path): List<Path> {
        Files.walk(root).use { paths ->
            return paths
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.toString().endsWith(".md") }
                .toList()
        }
    }

    private fun locateRepoRoot(): Path {
        val cwd = Path.of("").toAbsolutePath().normalize()
        return sequenceOf(cwd, cwd.parent)
            .filterNotNull()
            .firstOrNull { candidate -> candidate.resolve("docs").exists() && candidate.resolve("kast.sh").exists() }
            ?: error("Could not locate repo root from $cwd")
    }
}
