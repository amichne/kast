package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class TestPayload(val path: String, val sha256: String, val mode: Int)

@Serializable
private data class TestManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<TestPayload>,
    val semanticVersion: String,
)

class ManagementCliTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `retired container routes reject before runtime effects`() {
        for (arguments in listOf(listOf("container", "status"), listOf("status", "container"))) {
            val parsed = parseManagementCommand(arguments)
            assertTrue(parsed is ManagementParsing.Print && parsed.error, arguments.toString())
        }
    }

    @Test
    fun `destination policy retains every specified branch without requiring PATH`() {
        val home = temporary.resolve("home").toString()
        val default = selectDestination(home, null, null) as DestinationSelection.Selected
        assertEquals(Path.of(home, ".local/bin/kast"), default.executable)
        assertFalse(default.onPath)
        assertFalse((selectDestination(home, "", "") as DestinationSelection.Selected).onPath)
        assertTrue((selectDestination(home, null, "$home/.local/bin:/usr/bin") as DestinationSelection.Selected).onPath)
        val xdg = temporary.resolve("config")
        assertEquals(
            xdg.resolve("kast"),
            (selectDestination(home, xdg.toString(), "/usr/bin") as DestinationSelection.Selected).executable,
        )
        assertEquals(
            DestinationSelection.Rejected(DestinationFailure.XDG_RELATIVE),
            selectDestination(home, "relative/config", "/usr/bin"),
        )
    }
}

/** A product installation whose manifest also owns its bundled MCP launcher. */
internal fun integrationFixture(temporary: Path): Pair<Path, Path> {
    val product = productFixture(temporary)
    val installation = product.installation
    val root = installation.parent
    val home = Files.createDirectories(temporary.resolve("home"))
    val relative = "bin/kast-mcp-complete"
    val source = installation.resolve(relative)
    Files.createDirectories(source.parent)
    Files.writeString(source, "release adapter")
    source.toFile().setExecutable(true, false)
    val owned =
        listOf(relative, "share/kast/build-record.json", "share/kast/product-selection.json").map {
            TestPayload(it, "sha256:${sha256(installation.resolve(it))}", if (it == relative) 493 else 420)
        }
    Files.writeString(
        installation.resolve("installation.json"),
        Json.encodeToString(TestManifest(3, installation.toRealPath().toString(), owned, "1.2.3")),
    )
    val executable = temporary.resolve("kast-command")
    Files.writeString(executable, "native")
    writeTestManagementReceipt(root, executable, product.reference)
    return root to home
}
