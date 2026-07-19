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
    fun `one strict machine manifest selects its own CLI`() {
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
    fun `default manifest path is machine scoped`() {
        assertEquals(
            tempDir.resolve("Library/Application Support/Kast/machine/machine.json"),
            MacosMachineManifestLoader.defaultPath(tempDir),
        )
    }

    private fun writeMachineManifest(): Path {
        val root = tempDir.resolve("Library/Application Support/Kast/machine")
        val binary = root.resolve("bin/kast")
        val plugin = root.resolve("idea/kast.zip")
        val skill = root.resolve("resources/kast-skill/SKILL.md")
        val codex = root.resolve("resources/codex-marketplace/marketplace.json")
        Files.createDirectories(binary.parent)
        Files.createDirectories(plugin.parent)
        Files.createDirectories(skill.parent)
        Files.createDirectories(codex.parent)
        Files.writeString(binary, "binary")
        check(binary.toFile().setExecutable(true))
        Files.writeString(plugin, "plugin")
        Files.writeString(skill, "skill")
        Files.writeString(codex, "codex")
        return root.resolve("machine.json").also { manifest ->
            Files.writeString(
                manifest,
                """
                {
                  "type": "KAST_MACHINE_MANIFEST",
                  "cliSha256": "${sha256(binary)}",
                  "ideaPluginSha256": "${sha256(plugin)}",
                  "skillSha256": "${sha256(skill)}",
                  "codexSha256": "${directorySha256(codex.parent)}",
                  "schemaVersion": 1
                }
                """.trimIndent(),
            )
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun directorySha256(root: Path): String {
        val identity = Files.walk(root).use { entries ->
            entries
                .filter(Files::isRegularFile)
                .map { path -> "${root.relativize(path)}\n${sha256(path)}\n" }
                .sorted()
                .toList()
                .joinToString("")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
