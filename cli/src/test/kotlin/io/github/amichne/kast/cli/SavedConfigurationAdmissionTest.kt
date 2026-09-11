package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.InstalledCompositionFailure
import io.github.amichne.kast.cli.InstalledSidecarCacheRoot
import io.github.amichne.kast.cli.InstalledSidecarCacheRootAdmission
import io.github.amichne.kast.cli.projection.ProductInspectionDocuments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SavedConfigurationAdmissionTest {
    @Test
    fun `bare installed entry point reports every saved configuration rejection as complete passive evidence`(
        @TempDir temporary: Path,
    ) {
        val root = workspace(temporary)
        for ((marker, expected) in rejectionMarkers) {
            val result = launch(temporary, root, "io.github.amichne.kast.cli.KastCliMainKt", marker)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("", result.stderr)
            val document = Json.parseToJsonElement(result.stdout).jsonObject
            assertEquals("inspect", document.getValue("operation").jsonPrimitive.content)
            assertEquals("complete", document.getValue("status").jsonPrimitive.content)
            assertEquals("unobserved", document.getValue("runtime").jsonPrimitive.content)
            assertEquals("saved-configuration-$expected", document.getValue("blockingReason").jsonPrimitive.content)
            assertEquals(root.toRealPath().toString(), document.getValue("root").jsonPrimitive.content)
            assertFalse(Files.exists(temporary.resolve("runtime")))
            assertFalse(Files.exists(temporary.resolve("cache")))
        }
    }

    @Test
    fun `installed mutation entry point rejects saved configuration before runtime admission`(@TempDir temporary: Path) {
        val root = workspace(temporary)
        for ((marker, _) in rejectionMarkers) {
            val result = launch(
                temporary, root, "io.github.amichne.kast.cli.KastCliMainKt", marker,
                listOf("change", "plan"),
            )

            assertEquals(9, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
            val document = Json.parseToJsonElement(result.stderr).jsonObject
            assertEquals("composition_invalid", document.getValue("reason").jsonPrimitive.content)
            assertFalse(Files.exists(temporary.resolve("runtime")))
            assertFalse(Files.exists(temporary.resolve("cache")))
        }
    }

    @Test
    fun `live read entry point rejects unreadable saved read settings before opening a host`(@TempDir temporary: Path) {
        val result = launch(
            temporary, workspace(temporary), "io.github.amichne.kast.cli.KastCliMainKt", "unreadable",
            listOf("query", "run"), """{"type":"QUERY","from":{"type":"SEARCH","query":"Example"}}""",
        )

        assertEquals(4, result.exitCode, result.stderr)
        assertEquals("", result.stdout)
        val document = Json.parseToJsonElement(result.stderr).jsonObject
        assertEquals("ide-configuration-rejected", document.getValue("reason").jsonPrimitive.content)
        assertFalse(Files.exists(temporary.resolve("runtime")))
        assertFalse(Files.exists(temporary.resolve("cache")))
    }

    @Test
    fun `integration host rejects even empty and unknown markers before installation discovery or broker start`(
        @TempDir temporary: Path,
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

    @Test
    fun `invalid cache authority remains complete blocking evidence in passive document`(@TempDir temporary: Path) {
        val root = workspace(temporary)
        for (rawPath in listOf("", "relative/cache", "\u0000")) {
            val rejected = assertInstanceOf(
                InstalledSidecarCacheRootAdmission.Rejected::class.java,
                InstalledSidecarCacheRoot.admit(rawPath, temporary),
            )
            val exit = CliExit.Complete(
                ProductInspectionDocuments.blocked(
                    root, InstalledCompositionFailure.SidecarCacheRootRejected(rejected.failure),
                ),
            )

            assertEquals(0, exit.code)
            val document = Json.parseToJsonElement(exit.document.value).jsonObject
            assertEquals("inspect", document.getValue("operation").jsonPrimitive.content)
            assertEquals("complete", document.getValue("status").jsonPrimitive.content)
            assertEquals("cache-root-invalid_path", document.getValue("blockingReason").jsonPrimitive.content)
            assertEquals("unobserved", document.getValue("runtime").jsonPrimitive.content)
        }
    }

    private fun workspace(temporary: Path): Path = Files.createDirectory(temporary.resolve("repo")).also { root ->
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
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=$temporary",
            "-cp", System.getProperty("java.class.path"), mainClass,
        ) + arguments
        val process = ProcessBuilder(command)
            .directory(root.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .apply {
                environment().clear()
                environment().putAll(
                    mapOf(
                        "HOME" to temporary.toString(),
                        "KAST_SAVED_CONFIGURATION_FAILURE" to rejection,
                        "KAST_RUNTIME_DIRECTORY" to temporary.resolve("runtime").toString(),
                        "KAST_CACHE_ROOT" to temporary.resolve("cache").toString(),
                    ),
                )
            }.start()
        return try {
            process.outputStream.use { stream -> stream.write(input.toByteArray(Charsets.UTF_8)) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "entry point failed to terminate")
            ProcessEvidence(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private data class ProcessEvidence(val exitCode: Int, val stdout: String, val stderr: String)

    private val rejectionMarkers = listOf(
        "unreadable" to "unreadable",
        "duplicate-record" to "duplicate-record",
        "unsupported-record" to "unsupported-record",
        "" to "unknown",
        "unrecognized-value" to "unknown",
    )
}
