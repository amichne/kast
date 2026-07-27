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

class KastInstallReceiptLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `one active install receipt selects its verified CLI`() {
        val receipt = writeActiveInstallReceipt()

        val loaded = assertInstanceOf(
            KastInstallReceiptLoadResult.Loaded::class.java,
            KastInstallReceiptLoader.load(receipt) { CliImplementationVersion("1.2.3") },
        )

        assertEquals(receipt.parent.resolve("bin/kast").toRealPath(), loaded.binary)
        assertEquals("1.2.3", loaded.version.value)
    }

    @Test
    fun `manifest drift rejects the complete active release`() {
        val receipt = writeActiveInstallReceipt()
        Files.writeString(receipt.parent.resolve("manifest.json"), "modified")

        val rejected = assertInstanceOf(
            KastInstallReceiptLoadResult.Rejected::class.java,
            KastInstallReceiptLoader.load(receipt) { CliImplementationVersion("1.2.3") },
        )

        assertTrue(rejected.message.contains("modified"), rejected.message)
    }

    @Test
    fun `CLI drift rejects the complete active release`() {
        val receipt = writeActiveInstallReceipt()
        Files.writeString(receipt.parent.resolve("bin/kast"), "modified")

        val rejected = assertInstanceOf(
            KastInstallReceiptLoadResult.Rejected::class.java,
            KastInstallReceiptLoader.load(receipt) { CliImplementationVersion("1.2.3") },
        )

        assertTrue(rejected.message.contains("CLI"), rejected.message)
    }

    @Test
    fun `default receipt path is rooted in KAST_HOME current`() {
        assertEquals(
            tempDir.resolve(".local/share/kast/current/receipt.json"),
            KastInstallReceiptLoader.defaultPath(tempDir, kastHome = null),
        )
    }

    private fun writeActiveInstallReceipt(): Path {
        val current = tempDir.resolve(".local/share/kast/current")
        val binary = current.resolve("bin/kast")
        val manifest = current.resolve("manifest.json")
        Files.createDirectories(binary.parent)
        Files.writeString(binary, "binary")
        check(binary.toFile().setExecutable(true))
        Files.writeString(
            manifest,
            """
            {
              "artifacts": [
                {"role": "cli", "path": "bin/kast", "sha256": "${sha256(binary)}"}
              ]
            }
            """.trimIndent(),
        )
        return current.resolve("receipt.json").also { receipt ->
            Files.writeString(
                receipt,
                """
                {
                  "tool": "kast",
                  "releaseDigest": "${"a".repeat(64)}",
                  "manifestDigest": "${sha256(manifest)}",
                  "roots": {"install": "${tempDir.resolve(".local/share/kast")}"},
                  "entrypoints": {"activeBinary": "$binary"},
                  "schemaVersion": 3
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
