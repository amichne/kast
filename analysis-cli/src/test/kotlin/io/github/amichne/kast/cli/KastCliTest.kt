package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ServerLimits
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class KastCliTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `help prints human friendly text to stdout`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = KastCli().run(arrayOf("--help"), stdout, stderr)

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("Kast CLI"))
        assertTrue(stdout.toString().contains("Commands:"))
        assertEquals("", stderr.toString())
    }

    @Test
    fun `version prints human friendly text to stdout`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = KastCli().run(arrayOf("--version"), stdout, stderr)

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("Kast CLI"))
        assertEquals("", stderr.toString())
    }

    @Test
    fun `usage failures stay on stderr as json`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = KastCli().run(
            arrayOf("edits", "apply", "--workspace-root=$tempDir"),
            stdout,
            stderr,
        )
        val error = defaultCliJson().decodeFromString<CliErrorResponse>(stderr.toString())

        assertEquals(1, exitCode)
        assertEquals("", stdout.toString())
        assertEquals("CLI_USAGE", error.code)
        assertTrue(checkNotNull(error.details["usage"]).contains("edits apply"))
    }

    @Test
    fun `successful commands keep json on stdout and daemon notes on stderr`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val expectedNote = "daemon: using standalone daemon pid=42 ready at /tmp/workspace/.kast/s"
        val cli = KastCli(
            commandExecutorFactory = { _, _ ->
                object : CliCommandExecutor {
                    override suspend fun execute(command: CliCommand): CliExecutionResult {
                        return CliExecutionResult(
                            output = CliOutput.JsonValue(sampleCapabilities()),
                            daemonNote = expectedNote,
                        )
                    }
                }
            },
        )

        val exitCode = cli.run(
            arrayOf("capabilities", "--workspace-root=$tempDir"),
            stdout,
            stderr,
        )
        val capabilities = defaultCliJson().decodeFromString<BackendCapabilities>(stdout.toString())

        assertEquals(0, exitCode)
        assertEquals("standalone", capabilities.backendName)
        assertEquals("$expectedNote\n", stderr.toString())
    }

    private fun sampleCapabilities(): BackendCapabilities {
        return BackendCapabilities(
            backendName = "standalone",
            backendVersion = "0.1.0-SNAPSHOT",
            workspaceRoot = tempDir.toString(),
            readCapabilities = setOf(ReadCapability.DIAGNOSTICS),
            mutationCapabilities = setOf(MutationCapability.RENAME),
            limits = ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
            ),
        )
    }
}
