package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.ReleaseRevision
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
    fun `schema 3 revision-bound CLI receipt parses into trusted Homebrew authority`() {
        val binary = fakeBinary("1.2.3")
        val result = MacosHomebrewReceiptLoader.load(writeReceipt(binary, "1.2.3"))

        assertTrue(result is MacosHomebrewReceiptLoadResult.Loaded)
        val receipt = (result as MacosHomebrewReceiptLoadResult.Loaded).receipt
        assertEquals(binary.toRealPath(), receipt.cliBinary)
        assertEquals("1.2.3", receipt.cliVersion.value)
        assertEquals(ReleaseRevision(TEST_REVISION), receipt.cliRevision)
    }

    @Test
    fun `schema 2 receipt without release revision is rejected by the forward reader`() {
        val binary = fakeBinary("1.2.3")

        val result = MacosHomebrewReceiptLoader.load(
            writeReceipt(binary, "1.2.3", schemaVersion = 2, cliRevision = null),
        )

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `missing receipt is a typed expected failure`() {
        val result = MacosHomebrewReceiptLoader.load(tempDir.resolve("missing.json"))

        assertEquals(
            MacosHomebrewReceiptFailure.MISSING,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
        assertTrue(result.message.contains("repair --for machine --apply"))
    }

    @Test
    fun `schema 1 joint receipt is rejected by the forward reader`() {
        val binary = fakeBinary("1.2.3")
        val receipt = writeReceipt(
            binary,
            "1.2.3",
            schemaVersion = 1,
            pluginField = """, "plugin": {"caskToken":"amichne/kast/kast-plugin","version":"1.2.3"}""",
        )

        val result = MacosHomebrewReceiptLoader.load(receipt)

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `unknown plugin authority field is rejected`() {
        val binary = fakeBinary("1.2.3")
        val receipt = writeReceipt(binary, "1.2.3", pluginField = ", \"plugin\": {}")

        val result = MacosHomebrewReceiptLoader.load(receipt)

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `duplicate root and CLI keys are rejected before authority projection`() {
        val binary = fakeBinary("1.2.3")
        val duplicateRoot = writeReceipt(
            binary,
            "1.2.3",
            rootField = """, "authority": "macos-homebrew""",
        )
        val duplicateCli = writeReceipt(
            binary,
            "1.2.3",
            cliField = """, "version": "1.2.3""",
        )

        for (path in listOf(duplicateRoot, duplicateCli)) {
            assertEquals(
                MacosHomebrewReceiptFailure.INVALID,
                (MacosHomebrewReceiptLoader.load(path) as MacosHomebrewReceiptLoadResult.Rejected).failure,
            )
        }
    }

    @Test
    fun `formula prefix version must equal receipt CLI version`() {
        val binary = fakeBinary("1.2.3")

        val result = MacosHomebrewReceiptLoader.load(writeReceipt(binary, "1.2.4"))

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
        assertTrue(result.message.contains("Cellar/kast"))
    }

    @Test
    fun `missing receipt binary fails before workspace preparation`() {
        val binary = tempDir.resolve("Cellar/kast/1.2.3/bin/kast")
        val result = MacosHomebrewReceiptLoader.load(writeReceipt(binary, "1.2.3"))

        assertEquals(
            MacosHomebrewReceiptFailure.MISSING_BINARY,
            (result as MacosHomebrewReceiptLoadResult.Rejected).failure,
        )
    }

    @Test
    fun `malformed release revision is rejected`() {
        val binary = fakeBinary("1.2.3")

        val result = MacosHomebrewReceiptLoader.load(
            writeReceipt(binary, "1.2.3", cliRevision = "abc123"),
        )

        assertEquals(
            MacosHomebrewReceiptFailure.INVALID,
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

        val result = MacosHomebrewReceiptLoader.load(writeReceipt(formulaBinary, "1.2.3"))

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
        schemaVersion: Int = 3,
        cliRevision: String? = TEST_REVISION,
        pluginField: String = "",
        rootField: String = "",
        cliField: String = "",
    ): Path {
        val receipt = tempDir.resolve("receipt-${System.nanoTime()}.json")
        Files.createDirectories(binary.parent.parent)
        Files.writeString(
            receipt,
            """
            {
              "schemaVersion": $schemaVersion,
              "authority": "macos-homebrew",
              "cli": {
                "binary": "${binary.toString().jsonEscaped()}",
                "formulaPrefix": "${binary.parent.parent.toString().jsonEscaped()}",
                "version": "$cliVersion"${cliRevision?.let { revision -> ",\n                \"releaseRevision\": \"$revision\"" }.orEmpty()}$cliField
              }$pluginField$rootField,
              "updatedAt": "unix:1"
            }
            """.trimIndent(),
        )
        return receipt
    }

    private companion object {
        const val TEST_REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")
