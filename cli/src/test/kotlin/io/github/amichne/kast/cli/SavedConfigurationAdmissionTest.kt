package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedConfigurationAdmissionTest {
    @Test
    fun `bare installed entry point fails closed on every saved configuration rejection`(@TempDir temporary: Path) {
        val root = workspace(temporary)
        for ((marker, _) in rejectionMarkers) {
            val result = launch(temporary, root, "io.github.amichne.kast.cli.KastCliMainKt", marker)

            assertEquals(9, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
            val document = Json.parseToJsonElement(result.stderr).jsonObject
            assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
            assertEquals("bootstrap", document.getValue("boundary").jsonPrimitive.content)
            assertEquals(
                "configuration-saved_configuration_rejected",
                document.getValue("reason").jsonPrimitive.content,
            )
            assertFalse(Files.exists(temporary.resolve("runtime")))
            assertFalse(Files.exists(temporary.resolve("cache")))
        }
    }

    @Test
    fun `passive IDE status rejects saved configuration before host admission`(@TempDir temporary: Path) {
        val root = workspace(temporary)
        for ((marker, _) in rejectionMarkers) {
            val result =
                launch(
                    temporary = temporary,
                    root = root,
                    mainClass = "io.github.amichne.kast.cli.KastCliMainKt",
                    rejection = marker,
                    arguments = listOf("ide", "status"),
                )

            assertEquals(4, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
            val document = Json.parseToJsonElement(result.stderr).jsonObject
            assertEquals("ide-configuration-rejected", document.getValue("reason").jsonPrimitive.content)
            assertFalse(Files.exists(temporary.resolve("runtime")))
            assertFalse(Files.exists(temporary.resolve("cache")))
        }
    }

    @Test
    fun `canonical read requires daemon without opening a host`(@TempDir temporary: Path) {
        val result =
            launch(
                temporary,
                workspace(temporary),
                "io.github.amichne.kast.cli.KastCliMainKt",
                "unreadable",
                listOf("symbol", "discover"),
                Json.encodeToString(SymbolDiscoverFixture(NameTarget("name", "Example", "symbol", "fuzzy"), 10)),
            )

        assertEquals(4, result.exitCode, result.stderr)
        assertEquals("", result.stdout)
        val document = Json.parseToJsonElement(result.stderr).jsonObject
        assertEquals("daemon-operation-library-directory-invalid", document.getValue("reason").jsonPrimitive.content)
        assertFalse(Files.exists(temporary.resolve("runtime")))
        assertFalse(Files.exists(temporary.resolve("cache")))
    }

    @Serializable private data class SymbolDiscoverFixture(val target: NameTarget, val limit: Int)

    @Serializable
    private data class NameTarget(val type: String, val query: String, val kind: String, val match: String)

    @Test
    fun `hosted planning requires daemon without opening a host`(@TempDir temporary: Path) {
        val root = workspace(temporary)
        val request =
            ChangePlanRequest(
                ChangeIntentDocument.AddDeclaration(
                    (ProtocolText.parse("exact:opaque") as Refinement.Refined).value,
                    (ProtocolText.parse("fun added() = Unit") as Refinement.Refined).value,
                )
            )
        val result =
            launch(
                temporary,
                root,
                "io.github.amichne.kast.cli.KastCliMainKt",
                "unreadable",
                listOf("change", "plan"),
                Json.encodeToString(request),
            )
        assertEquals(4, result.exitCode, result.stderr)
        assertEquals("", result.stdout)
        val document = Json.parseToJsonElement(result.stderr).jsonObject
        assertEquals("daemon-operation-library-directory-invalid", document.getValue("reason").jsonPrimitive.content)
        assertFalse(Files.exists(temporary.resolve("runtime")))
        assertFalse(Files.exists(temporary.resolve("cache")))
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
