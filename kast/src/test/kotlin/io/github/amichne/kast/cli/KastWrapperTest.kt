package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.DiagnosticsResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

class KastWrapperTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `wrapper can ensure status capabilities and diagnostics through helper path`() {
        val workspace = tempDir.resolve("workspace")
        val sourceFile = workspace
            .resolve("src/main/kotlin/example/Sample.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package example

            fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )

        try {
            val ensure = runCli(
                "workspace",
                "ensure",
                "--workspace-root=$workspace",
            )
            val ensureResult = defaultCliJson().decodeFromString<WorkspaceEnsureResult>(ensure.stdout)
            assertEquals(workspace.toString(), ensureResult.workspaceRoot)
            assertEquals("uds", ensureResult.selected.descriptor.transport)
            assertTrue(ensure.stderr.contains("daemon:"))

            val status = runCli(
                "workspace",
                "status",
                "--workspace-root=$workspace",
            )
            val statusResult = defaultCliJson().decodeFromString<WorkspaceStatusResult>(status.stdout)
            assertEquals(1, statusResult.candidates.size)
            assertTrue(statusResult.selected?.ready == true)

            val capabilities = runCli(
                "capabilities",
                "--workspace-root=$workspace",
            )
            val capabilitiesResult = defaultCliJson().decodeFromString<BackendCapabilities>(capabilities.stdout)
            assertEquals("standalone", capabilitiesResult.backendName)

            val diagnostics = runCli(
                "diagnostics",
                "--workspace-root=$workspace",
                "--file-paths=$sourceFile",
            )
            val diagnosticsResult = defaultCliJson().decodeFromString<DiagnosticsResult>(diagnostics.stdout)
            assertTrue(diagnosticsResult.diagnostics.isEmpty())
            assertTrue(diagnostics.stderr.contains("daemon:"))
        } finally {
            runCli(
                "daemon",
                "stop",
                "--workspace-root=$workspace",
                allowFailure = true,
            )
        }
    }

    @Test
    fun `wrapper exposes bash completion script`() {
        val completion = runCli(
            "completion",
            "bash",
        )

        assertTrue(completion.stdout.contains("__kast_complete"))
        assertTrue(completion.stdout.contains("workspace"))
        assertEquals("", completion.stderr)
    }

    private fun runCli(
        vararg args: String,
        allowFailure: Boolean = false,
    ): ProcessResult {
        val wrapper = checkNotNull(System.getProperty("kast.wrapper")) {
            "kast.wrapper system property is missing"
        }
        val process = ProcessBuilder(listOf(wrapper) + args)
            .directory(Path.of("").toAbsolutePath().toFile())
            .start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        check(finished) { "kast wrapper timed out: ${args.joinToString(" ")}" }
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
        if (!allowFailure) {
            assertEquals(0, process.exitValue(), "stderr: $stderr")
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.trim(),
            stderr = stderr.trim(),
        )
    }

    private fun Path.createDirectoriesForParent(): Path {
        Files.createDirectories(checkNotNull(parent))
        return this
    }
}

private data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
