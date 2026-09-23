package io.github.amichne.kast.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedConfigurationAdmissionTest {
    @Test
    fun `private installer refuses former semantic and configuration commands`() {
        for (arguments in
            listOf(
                listOf("tool", "search_classes"),
                listOf("symbol", "discover"),
                listOf("config", "show"),
                listOf("codex"),
            )) {
            val exit = privateInstallerCommand(arguments)
            assertEquals(2, exit.code)
            assertTrue(exit is CliExit.BoundaryRejected)
            assertTrue(
                (exit as CliExit.BoundaryRejected).document.value.contains("unsupported-private-installer-command")
            )
        }
    }

    @Test
    fun `integration host rejects even empty and unknown markers before installation discovery or broker start`(
        @TempDir temporary: Path
    ) {
        val root = workspace(temporary)
        for ((marker, _) in rejectionMarkers) {
            val result = launch(temporary, root, KastCodexMain::class.java.name, marker)

            assertEquals(64, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
            assertEquals("kast-codex: configuration-rejected", result.stderr.trim())
            assertFalse(Files.exists(temporary.resolve("runtime")))
            assertFalse(Files.exists(temporary.resolve("cache")))
        }
    }

    private fun workspace(temporary: Path): Path =
        Files.createDirectory(temporary.resolve("repo")).also { root ->
            Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        }

    private fun launch(
        temporary: Path,
        root: Path,
        mainClass: String,
        rejection: String,
        arguments: List<String> = emptyList(),
        input: String = "",
    ): ProcessEvidence {
        val stdout = Files.createTempFile(temporary, "stdout-", ".txt")
        val stderr = Files.createTempFile(temporary, "stderr-", ".txt")
        val command =
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=$temporary",
                "-cp",
                System.getProperty("java.class.path"),
                mainClass,
            ) + arguments
        val process =
            ProcessBuilder(command)
                .directory(root.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply {
                    environment().clear()
                    environment()
                        .putAll(
                            mapOf(
                                "HOME" to temporary.toString(),
                                "KAST_SAVED_CONFIGURATION_FAILURE" to rejection,
                                "KAST_RUNTIME_DIRECTORY" to temporary.resolve("runtime").toString(),
                            )
                        )
                }
                .start()
        return try {
            process.outputStream.use { stream -> stream.write(input.toByteArray(Charsets.UTF_8)) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "entry point failed to terminate")
            ProcessEvidence(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private data class ProcessEvidence(val exitCode: Int, val stdout: String, val stderr: String)

    private val rejectionMarkers =
        listOf(
            "unreadable" to "unreadable",
            "duplicate-record" to "duplicate-record",
            "unsupported-record" to "unsupported-record",
            "" to "unknown",
            "unrecognized-value" to "unknown",
        )
}
