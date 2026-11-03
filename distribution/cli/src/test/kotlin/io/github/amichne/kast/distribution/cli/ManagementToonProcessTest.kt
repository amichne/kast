package io.github.amichne.kast.distribution.cli

import dev.toonformat.jtoon.JToon
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagementToonProcessTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `TOON switch selects structured status without a JSON flag`() {
        val standard = child(listOf("status", "--json"))
        val toon = child(listOf("status"), "1")
        assertEquals(0, standard.first)
        assertEquals(standard.first, toon.first)
        assertTrue(toon.second.startsWith("commandPath:"))
        assertEquals(Json.parseToJsonElement(standard.second), Json.parseToJsonElement(JToon.decodeToJson(toon.second)))
    }

    @Test
    fun `unset and empty output settings select TOON by default`() {
        val standard = child(listOf("status", "--json"))
        for (flag in listOf(null, "")) {
            val toon = child(listOf("status"), flag)
            assertEquals(standard.first, toon.first)
            assertTrue(toon.second.startsWith("commandPath:"))
            assertEquals(
                Json.parseToJsonElement(standard.second),
                Json.parseToJsonElement(JToon.decodeToJson(toon.second)),
            )
        }
    }

    @Test
    fun `startup rejection keeps its failure and nonzero exit in TOON`() {
        val result = child(listOf("query"), "1")
        assertEquals(4, result.first)
        assertEquals("failure: CATALOG_UNAVAILABLE\ntype: rejected\n", result.second)
    }

    @Test
    fun `unknown format rejects before uninstall effects`() {
        val result = child(listOf("uninstall", "--force"), "typo")
        assertEquals(2, result.first)
        assertTrue(result.second.contains("INVALID_OUTPUT_FORMAT"))
        assertTrue(Files.notExists(temporary.resolve(".local")))
    }

    private fun child(args: List<String>, flag: String? = "0"): Pair<Int, String> {
        val stdout = Files.createTempFile(temporary, "stdout", ".txt")
        val stderr = Files.createTempFile(temporary, "stderr", ".txt")
        val process =
            ProcessBuilder(
                    listOf(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "io.github.amichne.kast.distribution.cli.KastManagementMainKt",
                    ) + args
                )
                .directory(temporary.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply {
                    environment().clear()
                    environment()["HOME"] = temporary.toString()
                    if (flag == null) environment().remove("KAST_OUTPUT_TOON")
                    else environment()["KAST_OUTPUT_TOON"] = flag
                }
                .start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), Files.readString(stderr))
            return process.exitValue() to Files.readString(stdout)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}
