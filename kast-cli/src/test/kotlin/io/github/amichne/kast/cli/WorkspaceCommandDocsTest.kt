package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `installer guidance uses up as the primary standalone start command`() {
        val repoRoot = locateRepoRoot()
        val files = listOf(
            repoRoot.resolve("README.md"),
            repoRoot.resolve("docs/getting-started/install.md"),
            repoRoot.resolve("docs/getting-started/backends.md"),
            repoRoot.resolve("kast.sh"),
        )
        val staleGuidance = listOf(
            "Start with: kast daemon start",
            "start the standalone backend before",
            "Then start the standalone backend",
            "cd /your/kotlin/project && kast daemon start",
            "kast daemon start --workspace-root=/absolute/path/to/workspace",
        )
        val violations = files.flatMap { path ->
            val text = path.readText()
            staleGuidance
                .filter(text::contains)
                .map { snippet -> "$path contains $snippet" }
        }

        assertTrue(
            violations.isEmpty(),
            "Standalone lifecycle guidance should point users at kast up:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `cli cheat sheet lists every rpc method from the generated command catalog`() {
        val repoRoot = locateRepoRoot()
        val cheatSheet = repoRoot.resolve("docs/cli-cheat-sheet.md").readText()
        val commands = defaultCliJson()
            .parseToJsonElement(VersionedCommandSpec.renderJson(version = "test"))
            .jsonObject["commands"]!!
            .jsonObject
            .values
            .map { command -> command.jsonObject["method"]!!.jsonPrimitive.content }

        val missing = commands.filterNot { method -> cheatSheet.contains(method) }

        assertTrue(
            missing.isEmpty(),
            "docs/cli-cheat-sheet.md is missing RPC methods from commands.json:\n${missing.joinToString("\n")}",
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
