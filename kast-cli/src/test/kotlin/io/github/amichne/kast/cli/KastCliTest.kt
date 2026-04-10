package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ServerLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions

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
        assertTrue(stdout.toString().contains("Workspace lifecycle"))
        assertTrue(stdout.toString().contains("Validation"))
        assertTrue(stdout.toString().contains("call hierarchy"))
        assertTrue(stdout.toString().contains("smoke"))
        assertTrue(stdout.toString().contains("completion bash"))
        assertEquals("", stderr.toString())
    }

    @Test
    fun `completion prints shell script to stdout`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = KastCli().run(arrayOf("completion", "bash"), stdout, stderr)

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("__kast_complete"))
        assertTrue(stdout.toString().contains("complete -o default -o nospace -F __kast_complete kast"))
        assertEquals("", stderr.toString())
    }

    @Test
    fun `help can render ansi styling when requested`() {
        val help = CliCommandCatalog.helpText(
            topic = emptyList(),
            version = "dev",
            theme = CliTextTheme.ansi(),
        )

        assertTrue(help.contains("\u001B["))
        assertTrue(help.contains("Shell integration"))
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
            arrayOf("apply-edits", "--workspace-root=$tempDir"),
            stdout,
            stderr,
        )
        val error = defaultCliJson().decodeFromString<CliErrorResponse>(stderr.toString())

        assertEquals(1, exitCode)
        assertEquals("", stdout.toString())
        assertEquals("CLI_USAGE", error.code)
        assertTrue(checkNotNull(error.details["usage"]).contains("apply-edits"))
    }

    @Test
    fun `successful commands keep json on stdout and daemon notes on stderr`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val expectedNote = "daemon: using standalone daemon pid=42 ready at /tmp/workspace/.kast/s"
        val cli = KastCli.testInstance(
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

    @Test
    fun `external process output keeps shell stdout stderr and exit code`() {
        val script = tempDir.resolve("fixtures").createDirectories().resolve("smoke-fixture.sh").createFile()
        script.toFile().writeText(
            """
            #!/usr/bin/env bash
            printf 'shell stdout\n'
            printf 'shell stderr\n' >&2
            exit 7
            """.trimIndent(),
        )
        script.setPosixFilePermissions(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val cli = KastCli.testInstance(
            commandExecutorFactory = { _, _ ->
                object : CliCommandExecutor {
                    override suspend fun execute(command: CliCommand): CliExecutionResult {
                        return CliExecutionResult(
                            output = CliOutput.ExternalProcess(
                                CliExternalProcess(
                                    command = listOf("bash", script.toString()),
                                    workingDirectory = tempDir,
                                ),
                            ),
                        )
                    }
                }
            },
        )

        val exitCode = cli.run(arrayOf("--help"), stdout, stderr)

        assertEquals(7, exitCode)
        assertEquals("shell stdout\n", stdout.toString())
        assertEquals("shell stderr\n", stderr.toString())
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
