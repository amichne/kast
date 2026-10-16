package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Serializable
private data class PriorRegistration(val harness: Harness, val destination: String, val payloadSha256: String)

@Serializable
private data class PriorReceipt(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: ReleaseChannel,
    val registrations: List<PriorRegistration>,
)

class AppServerRegistrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `retired App Server registration rejects before effects`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        val rejection =
            assertThrows<ManagementRejected> {
                connectHarness(root, home, HarnessConnection.CODEX_APP_SERVER)
            }
        assertEquals("connection-retired", rejection.stage)
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
        assertFalse(Files.exists(root.resolve("management.lock")))
        assertFalse(Files.exists(home.resolve(".local/bin/kast-codex")))
        assertFalse(Files.exists(connectionConfigurationPath(home)))
    }

    @Test
    fun `receipt encoding retains each closed connection identity`() {
        val identities =
            listOf(
                HarnessConnection.CODEX_MCP to "CODEX_MCP",
                HarnessConnection.CODEX_APP_SERVER to "CODEX_APP_SERVER",
                HarnessConnection.COPILOT to "COPILOT",
                HarnessConnection.PI to "PI",
            )
        identities.forEach { (connection, expectedIdentity) ->
            val encoded =
                managementJson.encodeToString(ManagedRegistration(connection, "/owned/launcher", "a".repeat(64)))
            val document = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(setOf("connection", "destination", "payloadSha256"), document.keys)
            assertTrue(document.values.all { it.jsonPrimitive.isString })
            assertEquals(expectedIdentity, document.getValue("connection").jsonPrimitive.content)
            assertEquals("/owned/launcher", document.getValue("destination").jsonPrimitive.content)
            assertEquals("a".repeat(64), document.getValue("payloadSha256").jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown receipt connection and duplicate identity reject`() {
        val (root, _) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val existing = (readReceipt(root) as ReceiptRead.Read).receipt
        val registration = ManagedRegistration(HarnessConnection.CODEX_APP_SERVER, "/owned/launcher", "a".repeat(64))
        val valid = managementJson.encodeToString(existing.copy(registrations = listOf(registration)))
        Files.writeString(receiptPath(root), valid.replace("CODEX_APP_SERVER", "CODEX_UNKNOWN"))
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), readReceipt(root))
        writeManagementReceipt(root, existing.copy(registrations = listOf(registration, registration)))
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), readReceipt(root))
    }

    @Test
    fun `disconnect removes the exact receipt-owned legacy launcher`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val launcher = legacyAppServerRegistration(root, home)
        val config = home.resolve(".codex/config.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "model = \"existing-model\"\n")

        assertTrue(disconnectHarness(root, home, Harness.CODEX))

        assertFalse(Files.exists(launcher))
        assertEquals("model = \"existing-model\"\n", Files.readString(config))
        assertTrue((readReceipt(root) as ReceiptRead.Read).receipt.registrations.isEmpty())
    }

    @Test
    fun `disconnect preserves a foreign replacement and the receipt`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val launcher = legacyAppServerRegistration(root, home)
        Files.writeString(launcher, "foreign replacement")
        val receipt = Files.readAllBytes(receiptPath(root)).toList()

        assertThrows<ManagementRejected> { disconnectHarness(root, home, Harness.CODEX) }

        assertEquals("foreign replacement", Files.readString(launcher))
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
    }

    @Test
    fun `legacy receipt refines Codex ownership to MCP without rewriting files`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val existing = (readReceipt(root) as ReceiptRead.Read).receipt
        val destination = root.resolve("installation/bin/kast-mcp-complete").toString()
        val payloadDigest = "a".repeat(64)
        val prior =
            PriorReceipt(
                1,
                existing.installationRoot,
                existing.executable,
                existing.executableSha256,
                existing.channel,
                listOf(PriorRegistration(Harness.CODEX, destination, payloadDigest)),
            )
        val original = managementJson.encodeToString(prior)
        Files.writeString(receiptPath(root), original)
        val refined = (readReceipt(root) as ReceiptRead.Read).receipt
        assertEquals(2, refined.schemaVersion)
        assertEquals(
            listOf(ManagedRegistration(HarnessConnection.CODEX_MCP, destination, payloadDigest)),
            refined.registrations,
        )
        assertEquals(original, Files.readString(receiptPath(root)))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `disconnect rejects a replaced launcher parent preserving the foreign file and receipt`(
        launcherExists: Boolean
    ) {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        legacyAppServerRegistration(root, home)
        val directory = home.resolve(".local/bin")
        val retained = home.resolve(".local/retained-bin")
        val foreign = temporary.resolve("foreign-bin")
        Files.createDirectories(foreign)
        Files.move(directory, retained)
        val foreignLauncher = foreign.resolve("kast-codex")
        if (launcherExists) Files.copy(retained.resolve("kast-codex"), foreignLauncher)
        Files.createSymbolicLink(directory, foreign)
        val originalFile = if (launcherExists) Files.readAllBytes(foreignLauncher).toList() else emptyList()
        val originalReceipt = Files.readAllBytes(receiptPath(root)).toList()

        assertThrows<ManagementRejected> { disconnectHarness(root, home, Harness.CODEX) }

        assertEquals(launcherExists, Files.exists(foreignLauncher))
        if (launcherExists) assertEquals(originalFile, Files.readAllBytes(foreignLauncher).toList())
        assertEquals(originalReceipt, Files.readAllBytes(receiptPath(root)).toList())
        assertTrue(Files.isSymbolicLink(directory))
        assertTrue(Files.exists(retained.resolve("kast-codex")))
    }
}

internal fun legacyAppServerRegistration(root: Path, home: Path): Path {
    val launcher = home.resolve(".local/bin/kast-codex")
    Files.createDirectories(launcher.parent)
    Files.writeString(launcher, "owned legacy launcher\n")
    val receipt = (readReceipt(root) as ReceiptRead.Read).receipt
    writeManagementReceipt(
        root,
        receipt.copy(
            registrations =
                receipt.registrations +
                    ManagedRegistration(
                        HarnessConnection.CODEX_APP_SERVER,
                        launcher.toString(),
                        sha256(launcher),
                        launcher.parent.toString(),
                    )
        ),
    )
    return launcher
}
