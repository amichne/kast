package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class SessionExportHookTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exports markdown and html when enabled with a custom target dir`() {
        val sessionId = "c5b0a991-bfeb-4fd2-b903-48389a066c4b"
        val homeDir = tempDir.resolve("home").createDirectories()
        writeSessionEvents(homeDir, sessionId)

        val exportDir = tempDir.resolve("exports")
        val result = runSessionEndHook(
            homeDir = homeDir,
            sessionId = sessionId,
            exportDir = exportDir,
            enabled = true,
        )

        assertEquals(0, result.exitCode, result.stderr)

        val markdown = exportDir.resolve("copilot-session-$sessionId.md")
        val html = exportDir.resolve("copilot-session-$sessionId.html")
        assertTrue(markdown.exists())
        assertTrue(html.exists())
        assertTrue(markdown.readText().contains("### 👤 User"))
        assertTrue(markdown.readText().contains("### ✅ `skill`"))
        assertTrue(markdown.readText().contains("**kast**"))
        assertTrue(html.readText().contains("#1"))
        assertTrue(html.readText().contains("skill - kast"))
        assertTrue(html.readText().contains("**kast**"))
    }

    @Test
    fun `exports to the default kast sessions directory when no custom path is set`() {
        val sessionId = "97f81fa4-fed4-47da-a04f-7b1dfbac8aef"
        val homeDir = tempDir.resolve("home").createDirectories()
        writeSessionEvents(homeDir, sessionId)

        val result = runSessionEndHook(
            homeDir = homeDir,
            sessionId = sessionId,
            exportDir = null,
            enabled = true,
        )

        assertEquals(0, result.exitCode, result.stderr)

        val exportDir = homeDir.resolve(".kast/sessions")
        assertTrue(exportDir.resolve("copilot-session-$sessionId.md").exists())
        assertTrue(exportDir.resolve("copilot-session-$sessionId.html").exists())
    }

    @Test
    fun `does not export when disabled`() {
        val sessionId = "11111111-2222-3333-4444-555555555555"
        val homeDir = tempDir.resolve("home").createDirectories()
        writeSessionEvents(homeDir, sessionId)

        val exportDir = tempDir.resolve("exports")
        val result = runSessionEndHook(
            homeDir = homeDir,
            sessionId = sessionId,
            exportDir = exportDir,
            enabled = false,
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertFalse(exportDir.exists())
    }

    private fun runSessionEndHook(
        homeDir: Path,
        sessionId: String,
        exportDir: Path?,
        enabled: Boolean,
    ): HookResult {
        val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
        val hookScript = repoRoot.resolve(".github/hooks/session-end.sh")
        val processBuilder = ProcessBuilder("bash", hookScript.toString())
            .directory(repoRoot.toFile())

        val env = processBuilder.environment()
        env["HOME"] = homeDir.toString()
        env["KAST_SESSION_EXPORT"] = enabled.toString()
        if (exportDir != null) {
            env["KAST_SESSION_EXPORT_PATH"] = exportDir.toString()
        }

        val process = processBuilder.start()
        process.outputStream.use { output ->
            output.write(
                """
                {"reason":"complete","sessionId":"$sessionId","cwd":"${homeDir}/workspace"}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
            )
        }

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        check(finished) { "session-end hook timed out" }

        val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        return HookResult(process.exitValue(), stdout, stderr)
    }

    private fun writeSessionEvents(homeDir: Path, sessionId: String) {
        val sessionDir = homeDir.resolve(".copilot/session-state/$sessionId").createDirectories()
        Files.writeString(
            sessionDir.resolve("events.jsonl"),
            """
            {"type":"session.start","data":{"sessionId":"$sessionId","version":1,"producer":"copilot-agent","copilotVersion":"1.0.34","startTime":"2026-04-23T19:48:43.093Z","context":{"cwd":"${homeDir}/workspace","gitRoot":"${homeDir}/workspace","branch":"main","repository":"amichne/kast"},"alreadyInUse":false},"id":"session-start","timestamp":"2026-04-23T19:48:43.101Z","parentId":null}
            {"type":"user.message","data":{"messageId":"user-1","content":"Understand this Kotlin service and tell me what calls it."},"id":"user-1","timestamp":"2026-04-23T19:48:50.101Z","parentId":"session-start"}
            {"type":"assistant.message","data":{"messageId":"assistant-1","content":"I’ll inspect the service and trace its callers.","interactionId":"turn-1"},"id":"assistant-1","timestamp":"2026-04-23T19:48:55.101Z","parentId":"user-1"}
            {"type":"tool.execution_start","data":{"toolCallId":"call-1","toolName":"skill","arguments":{"skill":"kast"}},"id":"tool-start","timestamp":"2026-04-23T19:48:56.101Z","parentId":"assistant-1"}
            {"type":"tool.execution_complete","data":{"toolCallId":"call-1","model":"gpt-5.4-mini","interactionId":"turn-1","success":true,"result":{"content":"Skill \"kast\" loaded successfully.","detailedContent":"Follow the instructions in the skill context."},"toolTelemetry":{}},"id":"tool-complete","timestamp":"2026-04-23T19:48:56.201Z","parentId":"tool-start"}
            {"type":"session.shutdown","data":{"shutdownType":"routine","totalPremiumRequests":0.1,"totalApiDurationMs":1000,"sessionStartTime":1776973723093,"codeChanges":{"linesAdded":0,"linesRemoved":0,"filesModified":[]},"modelMetrics":{"gpt-5.4-mini":{"requests":{"count":1,"cost":0.1},"usage":{"inputTokens":10,"outputTokens":10,"cacheReadTokens":0,"cacheWriteTokens":0,"reasoningTokens":0}}},"currentModel":"gpt-5.4-mini","currentTokens":100,"systemTokens":10,"conversationTokens":80,"toolDefinitionsTokens":10},"id":"session-shutdown","timestamp":"2026-04-23T19:49:00.101Z","parentId":"tool-complete"}
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
    }

    private fun findRepoRoot(start: Path): Path = generateSequence(start.normalize()) { it.parent }
        .firstOrNull { candidate ->
            Files.isRegularFile(candidate.resolve(".github/hooks/session-end.sh"))
        }
        ?: error("Could not locate repo root from ${start}")

    private data class HookResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
