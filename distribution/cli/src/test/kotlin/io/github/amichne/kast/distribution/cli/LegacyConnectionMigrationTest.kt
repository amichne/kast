package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class LegacyConnectionMigrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `pending legacy replacement migrates only its recorded observed MCP command`() {
        val fixture = fixture(temporary)
        val transactionBefore = Files.readAllBytes(fixture.transaction)
        val script = MigrationCodexScript(fixture.config, fixture.legacyCommand, fixture.destination)

        assertFalse(
            connectHarness(
                fixture.root,
                fixture.home,
                HarnessConnection.CODEX_MCP,
                codexHome = fixture.config.parent,
                executeCodex = script::execute,
            )
        )

        script.assertConsumed()
        val registration = (readReceipt(fixture.root) as ReceiptRead.Read).receipt.registrations.single()
        assertEquals(
            ManagedRegistration(
                HarnessConnection.CODEX_MCP,
                fixture.destination.toString(),
                sha256(fixture.destination),
                fixture.config.parent.toString(),
            ),
            registration,
        )
        assertArrayEquals(transactionBefore, Files.readAllBytes(fixture.transaction))
        assertTrue(Files.isSymbolicLink(fixture.root.resolve("current")))
        assertEquals("legacy launcher", Files.readString(fixture.oldLauncher))
        assertEquals("new host configuration", Files.readString(fixture.config))
    }

    @Test
    fun `failed migration receipt restores complete config and receipt preimages`() {
        val fixture = fixture(temporary)
        val configBefore = Files.readAllBytes(fixture.config)
        val receiptBefore = Files.readAllBytes(receiptPath(fixture.root))
        val script = MigrationCodexScript(fixture.config, fixture.legacyCommand, fixture.destination)

        assertThrows<ManagementRejected> {
            connectHarness(
                fixture.root,
                fixture.home,
                HarnessConnection.CODEX_MCP,
                codexHome = fixture.config.parent,
                executeCodex = script::execute,
            ) { root, _ ->
                Files.writeString(receiptPath(root), "partial receipt")
                throw java.io.IOException("injected receipt failure")
            }
        }

        script.assertConsumed()
        assertArrayEquals(configBefore, Files.readAllBytes(fixture.config))
        assertArrayEquals(receiptBefore, Files.readAllBytes(receiptPath(fixture.root)))
        assertTrue(Files.isRegularFile(fixture.transaction, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `ordinary legacy destination has no migration authority`() {
        val fixture = fixture(temporary)
        Files.delete(fixture.transaction)
        rejectBeforeCodex(fixture, LegacyConnectionRejection.TRANSACTION_UNAVAILABLE)
    }

    @Test
    fun `legacy migration rejects malformed transaction and changed filesystem identities`() {
        val malformed = fixture(temporary.resolve("malformed"))
        Files.writeString(malformed.transaction, "invalid receipt")
        rejectBeforeCodex(malformed, LegacyConnectionRejection.TRANSACTION_REJECTED)

        val changed = fixture(temporary.resolve("changed"))
        val document = readTransaction(changed)
        writeTransaction(changed, document.copy(installationIdentity = document.installationIdentity.copy(inode = -1)))
        rejectBeforeCodex(changed, LegacyConnectionRejection.FILESYSTEM_IDENTITY_REJECTED)

        val selector = fixture(temporary.resolve("selector"))
        Files.delete(selector.root.resolve("current"))
        Files.createSymbolicLink(selector.root.resolve("current"), Path.of("versions/foreign"))
        rejectBeforeCodex(selector, LegacyConnectionRejection.FILESYSTEM_IDENTITY_REJECTED)
    }

    @Test
    fun `legacy migration rejects noncommitted transaction and changed prior payload`() {
        val prepared = fixture(temporary.resolve("prepared"))
        writeTransaction(prepared, readTransaction(prepared).copy(stage = InstallationReplacementStage.PREPARED))
        rejectBeforeCodex(prepared, LegacyConnectionRejection.TRANSACTION_REJECTED)

        val payload = fixture(temporary.resolve("payload"))
        Files.writeString(payload.oldLauncher, "foreign launcher")
        rejectBeforeCodex(payload, LegacyConnectionRejection.LEGACY_PAYLOAD_REJECTED)
    }

    @Test
    fun `legacy migration does not adopt missing foreign or already changed observed command`() {
        for (command in listOf<String?>(null, "foreign", "installation")) {
            val fixture = fixture(temporary.resolve(command ?: "absent"))
            val configBefore = Files.readAllBytes(fixture.config)
            val receiptBefore = Files.readAllBytes(receiptPath(fixture.root))
            var calls = 0
            val execute: (List<String>) -> ProcessObservation = { arguments ->
                calls++
                assertEquals(1, calls, "no Codex mutation is permitted")
                assertEquals(listOf("codex", "mcp", "list", "--json"), arguments)
                val observed =
                    when (command) {
                        null -> emptyList()
                        "installation" ->
                            listOf(MigrationServer("kast", MigrationTransport("stdio", fixture.destination.toString())))
                        else -> listOf(MigrationServer("kast", MigrationTransport("stdio", command)))
                    }
                ProcessObservation.Exited(0, managementJson.encodeToString(observed))
            }
            val failure =
                assertThrows<ManagementRejected> {
                    connectHarness(
                        fixture.root,
                        fixture.home,
                        HarnessConnection.CODEX_MCP,
                        codexHome = fixture.config.parent,
                        executeCodex = execute,
                    )
                }
            assertEquals("connect-migration", failure.stage)
            assertEquals("legacy_registration_unverified", failure.reason)
            assertEquals(1, calls)
            assertArrayEquals(configBefore, Files.readAllBytes(fixture.config))
            assertArrayEquals(receiptBefore, Files.readAllBytes(receiptPath(fixture.root)))
        }
    }

    private fun rejectBeforeCodex(fixture: MigrationFixture, reason: LegacyConnectionRejection) {
        val configBefore = Files.readAllBytes(fixture.config)
        val receiptBefore = Files.readAllBytes(receiptPath(fixture.root))
        val failure =
            assertThrows<ManagementRejected> {
                connectHarness(
                    fixture.root,
                    fixture.home,
                    HarnessConnection.CODEX_MCP,
                    codexHome = fixture.config.parent,
                    executeCodex = { throw AssertionError("Codex must not run") },
                )
            }
        assertEquals("connect-migration", failure.stage)
        assertEquals(reason.name.lowercase(), failure.reason)
        assertArrayEquals(configBefore, Files.readAllBytes(fixture.config))
        assertArrayEquals(receiptBefore, Files.readAllBytes(receiptPath(fixture.root)))
    }

    private fun fixture(owned: Path): MigrationFixture {
        Files.createDirectories(owned)
        val (root, home) = integrationFixture(owned.toRealPath(), HarnessConnection.CODEX_MCP, "new launcher")
        val old = root.resolve("versions/legacy-1")
        val oldLauncher = writeLegacyLauncher(old)
        Files.createSymbolicLink(root.resolve("current"), Path.of("versions/legacy-1"))
        val legacyCommand = root.resolve("current/bin/kast-mcp-complete")
        val prior = (readReceipt(root) as ReceiptRead.Read).receipt
        writeManagementReceipt(
            root,
            prior.copy(
                registrations =
                    listOf(
                        ManagedRegistration(HarnessConnection.CODEX_MCP, legacyCommand.toString(), sha256(oldLauncher))
                    )
            ),
        )
        val transaction = root.resolve("recovery/replacement/receipt.json")
        Files.createDirectories(transaction.parent)
        val document =
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PAYLOAD_COMMITTED,
                installation = root.resolve("installation").toString(),
                installationIdentity = identity(root.resolve("installation")),
                previous =
                    PreviousInstallationPayload.Legacy(
                        installation = old.toString(),
                        identity = identity(old),
                        selector = root.resolve("current").toString(),
                        target = "versions/legacy-1",
                        recovery = transaction.parent.resolve("recovery").toString(),
                    ),
            )
        Files.writeString(transaction, managementJson.encodeToString(document))
        val config = home.resolve(".codex/config.toml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            "model = \"preserve\"\n[mcp_servers.kast]\ncommand = \"$legacyCommand\"\n" +
                "[mcp_servers.other]\ncommand = \"unrelated\"\n",
        )
        return MigrationFixture(root, home, config, transaction, legacyCommand, oldLauncher)
    }
}

private fun writeLegacyLauncher(installation: Path): Path {
    val launcher = installation.resolve("bin/kast-mcp-complete")
    Files.createDirectories(launcher.parent)
    Files.writeString(launcher, "legacy launcher")
    Files.writeString(
        installation.resolve("installation.json"),
        managementJson.encodeToString(
            BundledManifest(
                2,
                installation.toString(),
                listOf(BundledPayload("bin/kast-mcp-complete", "sha256:${sha256(launcher)}", 493)),
            )
        ),
    )
    return launcher
}

private data class MigrationFixture(
    val root: Path,
    val home: Path,
    val config: Path,
    val transaction: Path,
    val legacyCommand: Path,
    val oldLauncher: Path,
) {
    val destination: Path
        get() = root.resolve("installation/bin/kast-mcp-complete")
}

private fun identity(path: Path): InstallationFilesystemIdentity =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )

private fun readTransaction(fixture: MigrationFixture): InstallationReplacementReceipt =
    managementJson.decodeFromString(InstallationReplacementReceipt.serializer(), Files.readString(fixture.transaction))

private fun writeTransaction(fixture: MigrationFixture, receipt: InstallationReplacementReceipt) {
    Files.writeString(fixture.transaction, managementJson.encodeToString(receipt))
}

@Serializable private data class MigrationServer(val name: String, val transport: MigrationTransport)

@Serializable private data class MigrationTransport(val type: String, val command: String)

private class MigrationCodexScript(val config: Path, val oldCommand: Path, val destination: Path) {
    private var calls = 0

    fun execute(arguments: List<String>): ProcessObservation {
        calls++
        return when (calls) {
            1,
            3 -> {
                assertEquals(listOf("codex", "mcp", "list", "--json"), arguments)
                val command = if (calls == 1) oldCommand else destination
                ProcessObservation.Exited(
                    0,
                    managementJson.encodeToString(
                        listOf(
                            MigrationServer(
                                "kast",
                                MigrationTransport("stdio", command.toString()),
                            )
                        )
                    ),
                )
            }
            2 -> {
                assertEquals(listOf("codex", "mcp", "add", "kast", "--", destination.toString()), arguments)
                Files.writeString(config, "new host configuration")
                ProcessObservation.Exited(0, "")
            }
            else -> throw AssertionError("unexpected Codex invocation $arguments")
        }
    }

    fun assertConsumed() {
        assertEquals(3, calls, "every scripted invocation must be consumed")
    }
}
