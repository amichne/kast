package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MacosHomebrewInstallReceiptTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `valid receipt parses into trusted Homebrew authority`() {
        val pluginVersion = PluginVersion("1.2.3")
        val binary = fakeBinary("1.2.3")
        val receipt = writeReceipt(binary = binary, cliVersion = "1.2.3", pluginVersion = "1.2.3")

        val result = MacosHomebrewReceiptLoader.load(receipt, pluginVersion)

        assertTrue(result is MacosHomebrewReceiptLoadResult.Loaded)
        assertEquals(binary.toRealPath(), (result as MacosHomebrewReceiptLoadResult.Loaded).receipt.cliBinary)
    }

    @Test
    fun `missing receipt is a typed expected failure`() {
        val result = MacosHomebrewReceiptLoader.load(
            tempDir.resolve("missing.json"),
            PluginVersion("1.2.3"),
        )

        assertEquals(
            MacosHomebrewReceiptFailure.MISSING,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `malformed receipt is a typed expected failure`() {
        val receipt = tempDir.resolve("malformed.json")
        Files.writeString(receipt, "not-json")

        val result = MacosHomebrewReceiptLoader.load(receipt, PluginVersion("1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `receipt without completion timestamp is a typed expected failure`() {
        val binary = fakeBinary("1.2.3")
        val receipt = writeReceipt(
            binary = binary,
            cliVersion = "1.2.3",
            pluginVersion = "1.2.3",
            includeUpdatedAt = false,
        )

        val result = MacosHomebrewReceiptLoader.load(receipt, PluginVersion("1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `invalid binary path is a typed expected failure`() {
        val receipt = tempDir.resolve("invalid-path.json")
        Files.writeString(
            receipt,
            """
            {
              "schemaVersion": 1,
              "authority": "macos-homebrew",
              "cli": {
                "binary": "bad\u0000path",
                "formulaPrefix": "/opt/homebrew/Cellar/kast/1.2.3",
                "version": "1.2.3"
              },
              "plugin": {
                "caskToken": "amichne/kast/kast-plugin",
                "version": "1.2.3"
              }
            }
            """.trimIndent(),
        )

        val result = MacosHomebrewReceiptLoader.load(receipt, PluginVersion("1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `stale receipt versions fail before workspace preparation`() {
        val binary = fakeBinary("1.2.2")
        val receipt = writeReceipt(binary = binary, cliVersion = "1.2.2", pluginVersion = "1.2.2")

        val result = MacosHomebrewReceiptLoader.load(receipt, PluginVersion("1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.VERSION_MISMATCH,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `missing receipt binary fails before workspace preparation`() {
        val binary = tempDir.resolve("Cellar/kast/1.2.3/bin/kast")
        val receipt = writeReceipt(binary = binary, cliVersion = "1.2.3", pluginVersion = "1.2.3")

        val result = MacosHomebrewReceiptLoader.load(receipt, PluginVersion("1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.MISSING_BINARY,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `receipt binary symlink cannot escape the Homebrew formula`() {
        val outsideBinary = tempDir.resolve("outside/kast")
        Files.createDirectories(outsideBinary.parent)
        Files.writeString(outsideBinary, "#!/usr/bin/env sh\n")
        check(outsideBinary.toFile().setExecutable(true))
        val formulaBinary = tempDir.resolve("Cellar/kast/1.2.3/bin/kast")
        Files.createDirectories(formulaBinary.parent)
        Files.createSymbolicLink(formulaBinary, outsideBinary)
        val receipt = writeReceipt(
            binary = formulaBinary,
            cliVersion = "1.2.3",
            pluginVersion = "1.2.3",
        )

        val result = MacosHomebrewReceiptLoader.load(receipt, PluginVersion("1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `default receipt path uses macOS application support`() {
        assertEquals(
            tempDir.resolve("Library/Application Support/Kast/homebrew-install.json"),
            defaultMacosHomebrewReceiptPath(tempDir),
        )
    }

    private fun fakeBinary(version: String): Path {
        val binary = tempDir.resolve("Cellar/kast/$version/bin/kast")
        Files.createDirectories(binary.parent)
        Files.writeString(binary, "#!/usr/bin/env sh\n")
        check(binary.toFile().setExecutable(true))
        return binary
    }

    private fun writeReceipt(
        binary: Path,
        cliVersion: String,
        pluginVersion: String,
        includeUpdatedAt: Boolean = true,
    ): Path {
        val receipt = tempDir.resolve("receipt-${System.nanoTime()}.json")
        Files.writeString(
            receipt,
            """
            {
              "schemaVersion": 1,
              "authority": "macos-homebrew",
              "cli": {
                "binary": "${binary.toString().jsonEscaped()}",
                "formulaPrefix": "${binary.parent.parent.toString().jsonEscaped()}",
                "version": "$cliVersion"
              },
              "plugin": {
                "caskToken": "amichne/kast/kast-plugin",
                "version": "$pluginVersion"
              }${if (includeUpdatedAt) ",\n  \"updatedAt\": \"unix:1\"" else ""}
            }
            """.trimIndent(),
        )
        return receipt
    }
}

private fun String.jsonEscaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
