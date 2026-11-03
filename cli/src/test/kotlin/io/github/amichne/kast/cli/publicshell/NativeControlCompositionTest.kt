package io.github.amichne.kast.cli.publicshell

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** One actual executable, without adjacent launchers, libraries, or an installed Host. */
@Tag("public-launcher")
class NativeControlCompositionTest {
    @Test
    fun `one native executable serves query metadata and service rejection`(@TempDir directory: Path) {
        val executable = Files.copy(Path.of(System.getProperty("kast.publicLauncher")), directory.resolve("kast"))
        val query = run(executable, directory, "query", "describe")
        assertEquals(0, query.first, query.second)
        assertEquals(
            "kast.cli.describe.v1",
            Json.parseToJsonElement(query.second).jsonObject.getValue("schema").jsonPrimitive.content,
        )
        assertEquals(64, run(executable, directory, "service", "unknown").first)
    }

    @Test
    fun `unknown native entry rejects before command dispatch`(@TempDir directory: Path) {
        val executable = Path.of(System.getProperty("kast.publicLauncher"))
        assertEquals(2, run(executable, directory, "unknown", "describe").first)
    }

    private fun run(executable: Path, directory: Path, entry: String, vararg arguments: String): Pair<Int, String> {
        val output = directory.resolve("$entry.stdout")
        val child =
            ProcessBuilder(listOf(executable.toString(), "--internal-entrypoint=$entry") + arguments)
                .directory(directory.toFile())
                .redirectOutput(output.toFile())
                .redirectError(directory.resolve("$entry.stderr").toFile())
                .apply {
                    environment().clear()
                    environment()["HOME"] = directory.toString()
                    environment()["PATH"] = "/usr/bin:/bin"
                    environment()["KAST_OUTPUT_TOON"] = "0"
                }
                .start()
        child.outputStream.close()
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Native entry did not settle")
            return child.exitValue() to Files.readString(output)
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }
}
