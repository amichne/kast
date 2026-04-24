package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RoutingCorpusScriptTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `routing corpus script emits sanitized cases and promotion candidates`() {
        val sessionsDir = tempDir.resolve("sessions").createDirectories()
        sessionsDir.resolve("copilot-session-123.md").writeText(
            """
            # 🤖 Copilot CLI Session
            > - **Session ID:** `7594bd77-995a-49c0-80e7-5608b915770a`

            ### 👤 User

            Understand this Kotlin service and tell me what calls it in @/Users/amichne/code/kast/src/main/kotlin/example/Service.kt

            ---

            ### ✅ `skill`

            **kast**

            ---

            ### ✅ `bash`

            **Inspect Kotlin service**
            """.trimIndent(),
        )

        val logsDir = tempDir.resolve("logs").createDirectories()
        logsDir.resolve("process-1.log").writeText(
            """
            2026-04-19T07:50:16.996Z [INFO] Workspace initialized: 094d1e99-eb99-4e1e-bfd7-d4da1b2aa782 (checkpoints: 0)
            2026-04-19T07:50:17.678Z [WARNING] .github/agents/kast.md: unknown field ignored: agents
            2026-04-19T08:02:09.681Z [INFO] Custom agent "explore" invoked with prompt: Explore the Kotlin service and trace the call graph
            2026-04-19T08:02:09.687Z [INFO] Custom agent "explore" using tools: grep, glob, view, bash
            """.trimIndent(),
        )

        val outputJsonl = tempDir.resolve("routing-cases.jsonl")
        val outputMarkdown = tempDir.resolve("routing-summary.md")
        val outputPromotions = tempDir.resolve("promotion-candidates.json")

        val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
        val scriptPath = repoRoot.resolve(".agents/skills/kast/fixtures/maintenance/scripts/build-routing-corpus.py")
        val process = ProcessBuilder(
            "python3",
            scriptPath.toString(),
            "--session-dir=${sessionsDir}",
            "--logs-dir=${logsDir}",
            "--output-jsonl=${outputJsonl}",
            "--output-markdown=${outputMarkdown}",
            "--output-promotions=${outputPromotions}",
        )
            .directory(repoRoot.toFile())
            .start()

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        check(finished) { "routing corpus script timed out" }
        assertEquals(0, process.exitValue(), process.errorStream.readAllBytes().toString(Charsets.UTF_8))

        val jsonl = outputJsonl.readText()
        assertTrue(jsonl.contains("loaded-but-bypassed"))
        assertTrue(jsonl.contains("route-via-subagent"))
        assertTrue(jsonl.contains("<ABS_PATH>"))

        val markdown = outputMarkdown.readText()
        assertTrue(markdown.contains("Kast routing corpus summary"))
        assertTrue(markdown.contains("config-drift"))

        val promotions = outputPromotions.readText()
        assertTrue(promotions.contains("\"expected_skill\": \"kast\""))
        assertTrue(promotions.contains("\"expected_route\": \"@kast\""))
    }

    private fun findRepoRoot(start: Path): Path = generateSequence(start.normalize()) { it.parent }
        .firstOrNull { candidate ->
            Files.isRegularFile(
                candidate.resolve(".agents/skills/kast/fixtures/maintenance/scripts/build-routing-corpus.py"),
            )
        }
        ?: error("Could not locate repo root from ${start}")
}
