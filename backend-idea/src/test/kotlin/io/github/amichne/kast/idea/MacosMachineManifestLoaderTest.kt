package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class MacosMachineManifestLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `machine manifest selects its own CLI`() {
        val manifest = writeMachineManifest()

        val loaded = assertInstanceOf(
            MacosMachineManifestLoadResult.Loaded::class.java,
            MacosMachineManifestLoader.load(manifest) { CliImplementationVersion("1.2.3") },
        )

        assertEquals(manifest.parent.resolve("bin/kast").toRealPath(), loaded.binary)
        assertEquals("1.2.3", loaded.version.value)
    }

    @Test
    fun `component drift rejects the complete machine bundle`() {
        val manifest = writeMachineManifest()
        Files.writeString(manifest.parent.resolve("idea/kast.zip"), "modified")

        val rejected = assertInstanceOf(
            MacosMachineManifestLoadResult.Rejected::class.java,
            MacosMachineManifestLoader.load(manifest) { CliImplementationVersion("1.2.3") },
        )

        assertTrue(rejected.message.contains("modified"), rejected.message)
    }

    @Test
    fun `legacy guidance digests are ignored`() {
        val manifest = writeMachineManifest(
            schemaVersion = 1,
            obsoleteFields = """
              "skillSha256": "dirty",
              "codexSha256": "also-dirty",
            """.trimIndent(),
        )

        val loaded = assertInstanceOf(
            MacosMachineManifestLoadResult.Loaded::class.java,
            MacosMachineManifestLoader.load(manifest) { CliImplementationVersion("1.2.3") },
        )

        assertEquals(manifest.parent.resolve("bin/kast").toRealPath(), loaded.binary)
        assertTrue(Files.notExists(manifest.parent.resolve("resources")))
    }

    @Test
    fun `default manifest path is machine scoped`() {
        assertEquals(
            tempDir.resolve("Library/Application Support/Kast/machine/machine.json"),
            MacosMachineManifestLoader.defaultPath(tempDir),
        )
    }

    private fun writeMachineManifest(
        schemaVersion: Int = 3,
        obsoleteFields: String = "",
    ): Path {
        val root = tempDir.resolve("Library/Application Support/Kast/machine")
        val binary = root.resolve("bin/kast")
        val plugin = root.resolve("idea/kast.zip")
        Files.createDirectories(binary.parent)
        Files.createDirectories(plugin.parent)
        Files.writeString(binary, "binary")
        check(binary.toFile().setExecutable(true))
        Files.writeString(plugin, "plugin")
        return root.resolve("machine.json").also { manifest ->
            Files.writeString(
                manifest,
                """
                {
                  "type": "KAST_MACHINE_MANIFEST",
                  "cliSha256": "${sha256(binary)}",
                  "ideaPluginSha256": "${sha256(plugin)}",
                  $obsoleteFields
                  "schemaVersion": $schemaVersion
                }
                """.trimIndent(),
            )
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

}
