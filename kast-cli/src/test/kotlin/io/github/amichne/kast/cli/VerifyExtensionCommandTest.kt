package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.results.VerifyExtensionResult
import io.github.amichne.kast.cli.tty.CliCommand
import io.github.amichne.kast.cli.tty.CliOutput
import io.github.amichne.kast.cli.tty.CliService
import io.github.amichne.kast.cli.tty.DefaultCliCommandExecutor
import io.github.amichne.kast.cli.tty.currentCliVersion
import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VerifyExtensionCommandTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `executor reports matching extension marker as ok`() = runTest {
        writeInstalledExtensionVersion(currentCliVersion())
        val executor = DefaultCliCommandExecutor(
            cliService = CliService(defaultCliJson()),
            cwdProvider = { tempDir },
        )

        val execution = executor.execute(CliCommand.VerifyExtension)

        val output = execution.output as CliOutput.JsonValueWithExitCode
        val result = output.value as VerifyExtensionResult
        assertEquals(0, output.exitCode)
        assertTrue(result.ok)
        assertEquals(currentCliVersion(), result.cliVersion)
        assertEquals(currentCliVersion(), result.extensionVersion)
    }

    @Test
    fun `executor finds marker from installed extension directory`() = runTest {
        writeInstalledExtensionVersion(currentCliVersion())
        val extensionDir = tempDir.resolve(".github/extensions/kast")
        Files.createDirectories(extensionDir)
        val executor = DefaultCliCommandExecutor(
            cliService = CliService(defaultCliJson()),
            cwdProvider = { extensionDir },
        )

        val execution = executor.execute(CliCommand.VerifyExtension)

        val output = execution.output as CliOutput.JsonValueWithExitCode
        val result = output.value as VerifyExtensionResult
        assertEquals(0, output.exitCode)
        assertTrue(result.ok)
        assertEquals(currentCliVersion(), result.extensionVersion)
    }

    @Test
    fun `cli exits non zero and writes json when extension marker differs`() {
        writeInstalledExtensionVersion("stale-extension")
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = withWorkingDirectory(tempDir) {
            KastCli().run(arrayOf("verify-extension"), stdout, stderr)
        }

        val response = defaultCliJson().parseToJsonElement(stdout.toString()).jsonObject
        assertEquals(1, exitCode)
        assertEquals("", stderr.toString())
        assertEquals(false, response.getValue("ok").jsonPrimitive.boolean)
        assertEquals(currentCliVersion(), response.getValue("cli_version").jsonPrimitive.content)
        assertEquals("stale-extension", response.getValue("extension_version").jsonPrimitive.content)
    }

    private fun writeInstalledExtensionVersion(version: String) {
        val githubDir = tempDir.resolve(".github")
        Files.createDirectories(githubDir)
        Files.writeString(githubDir.resolve(".kast-copilot-version"), "$version\n")
    }

    private fun <T> withWorkingDirectory(
        cwd: Path,
        block: () -> T,
    ): T {
        val previous = System.getProperty("user.dir")
        System.setProperty("user.dir", cwd.toString())
        return try {
            block()
        } finally {
            System.setProperty("user.dir", previous)
        }
    }
}
