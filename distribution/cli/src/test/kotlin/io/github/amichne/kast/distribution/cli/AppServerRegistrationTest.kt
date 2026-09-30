package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
    fun `App Server installs an owned executable facade without touching Codex configuration`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val config = home.resolve(".codex/config.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "model = \"existing-model\"\n")
        val priorConfig = Files.readAllBytes(config).toList()
        val launcher = home.resolve(".local/bin/kast-codex")
        assertFalse(
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_APP_SERVER,
                executeCodex = { error("unexpected MCP call") },
            )
        )
        assertEquals(
            "#!/bin/sh\nexec '${root.resolve("current/bin/kast-codex-complete")}' \"\$@\"\n",
            Files.readString(launcher),
        )
        assertTrue(Files.isExecutable(launcher))
        val receipt = (readReceipt(root) as ReceiptRead.Read).receipt
        assertEquals(2, receipt.schemaVersion)
        assertEquals(
            listOf(ManagedRegistration(HarnessConnection.CODEX_APP_SERVER, launcher.toString(), sha256(launcher))),
            receipt.registrations,
        )
        assertTrue(Files.readString(receiptPath(root)).contains("\"connection\":\"CODEX_APP_SERVER\""))
        assertTrue(
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_APP_SERVER,
                executeCodex = { error("unexpected MCP call") },
            )
        )
        assertEquals(priorConfig, Files.readAllBytes(config).toList())
        assertTrue(disconnectHarness(root, home, Harness.CODEX))
        assertFalse(Files.exists(launcher))
        assertEquals(priorConfig, Files.readAllBytes(config).toList())
    }

    @Test
    fun `launcher forwards arguments to the release facade through a real child`() {
        val (root, home) =
            integrationFixture(
                temporary.resolve("root with ' quote"),
                HarnessConnection.CODEX_APP_SERVER,
                "#!/bin/sh\nprintf '%s\\n' \"facade:\$1|\$2\"\n",
            )
        connectHarness(root, home, HarnessConnection.CODEX_APP_SERVER, executeCodex = { error("unexpected MCP call") })
        val child = ProcessBuilder(home.resolve(".local/bin/kast-codex").toString(), "app-server", "--help").start()
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            assertEquals("facade:app-server|--help\n", child.inputStream.bufferedReader().readText())
            assertEquals("", child.errorStream.bufferedReader().readText())
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    @Test
    fun `foreign launcher rejects and forced receipt failure restores exact preimages`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val launcher = home.resolve(".local/bin/kast-codex")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "foreign launcher\n")
        val receiptBefore = Files.readAllBytes(receiptPath(root)).toList()
        assertThrows<ManagementRejected> {
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_APP_SERVER,
                executeCodex = { error("unexpected MCP call") },
            )
        }
        assertEquals("foreign launcher\n", Files.readString(launcher))
        assertEquals(receiptBefore, Files.readAllBytes(receiptPath(root)).toList())
        assertThrows<ManagementRejected> {
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_APP_SERVER,
                RegistrationOwnership.REPLACE_SELECTED_SLOT,
                executeCodex = { error("unexpected MCP call") },
            ) { _, _ ->
                Files.writeString(receiptPath(root), "partial receipt")
                throw IOException("case-owned receipt failure")
            }
        }
        assertEquals("foreign launcher\n", Files.readString(launcher))
        assertEquals(receiptBefore, Files.readAllBytes(receiptPath(root)).toList())
    }

    @Test
    fun `legacy receipt refines Codex ownership to MCP without rewriting files`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val existing = (readReceipt(root) as ReceiptRead.Read).receipt
        val destination = root.resolve("current/bin/kast-mcp-complete").toString()
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
        connectHarness(root, home, HarnessConnection.CODEX_APP_SERVER, executeCodex = { error("unexpected MCP call") })
        assertEquals(
            listOf(HarnessConnection.CODEX_MCP, HarnessConnection.CODEX_APP_SERVER),
            (readReceipt(root) as ReceiptRead.Read).receipt.registrations.map { it.connection },
        )
    }

    @Test
    fun `changed release facade rejects before publishing launcher or receipt`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val before = Files.readAllBytes(receiptPath(root)).toList()
        Files.writeString(root.resolve("current/bin/kast-codex-complete"), "changed facade")
        assertThrows<ManagementRejected> {
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_APP_SERVER,
                executeCodex = { error("unexpected MCP call") },
            )
        }
        assertFalse(Files.exists(home.resolve(".local/bin/kast-codex")))
        assertEquals(before, Files.readAllBytes(receiptPath(root)).toList())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `disconnect rejects a replaced launcher parent preserving the foreign file and receipt`(
        launcherExists: Boolean
    ) {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        connectHarness(root, home, HarnessConnection.CODEX_APP_SERVER, executeCodex = { error("unexpected MCP call") })
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
