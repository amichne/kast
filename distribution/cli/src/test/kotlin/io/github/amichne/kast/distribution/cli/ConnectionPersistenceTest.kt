package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class ConnectionPersistenceTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(HarnessConnection::class, names = ["PI", "COPILOT", "CODEX_APP_SERVER"])
    fun selectedDirectorySurvivesReconnectRelocationAndDisconnect(connection: HarnessConnection) {
        val (root, home) = integrationFixture(temporary, connection)
        val first = temporary.resolve("custom one")
        val second = temporary.resolve("custom two")
        val firstTarget = expectedTarget(first, connection)
        val secondTarget = expectedTarget(second, connection)
        assertFalse(connectHarness(root, home, connection, directory = admittedConnectionDirectory(first.toString())))
        assertTrue(Files.exists(firstTarget))
        assertSavedDirectory(home, connection, first)
        assertTrue(
            connectHarness(
                root,
                home,
                connection,
                environment =
                    mapOf(
                        "PI_CODING_AGENT_DIR" to second.toString(),
                        "COPILOT_HOME" to second.toString(),
                    ),
            )
        )
        assertFalse(Files.exists(secondTarget))
        assertFalse(connectHarness(root, home, connection, directory = admittedConnectionDirectory(second.toString())))
        assertFalse(Files.exists(firstTarget))
        assertTrue(Files.exists(secondTarget))
        assertSavedDirectory(home, connection, second)
        assertTrue(disconnectHarness(root, home, connection.harness))
        assertFalse(Files.exists(secondTarget))
        assertSavedDirectory(home, connection, second)
        connectHarness(root, home, connection)
        assertTrue(Files.exists(secondTarget))
    }

    @ParameterizedTest
    @EnumSource(HarnessConnection::class, names = ["PI", "COPILOT"])
    fun firstConnectionHonorsHarnessEnvironment(connection: HarnessConnection) {
        val (root, home) = integrationFixture(temporary, connection)
        val selected = temporary.resolve("environment")
        val key =
            when (connection) {
                HarnessConnection.PI -> "PI_CODING_AGENT_DIR"
                HarnessConnection.COPILOT -> "COPILOT_HOME"
                else -> throw AssertionError("unsupported fixture")
            }
        connectHarness(root, home, connection, environment = mapOf(key to selected.toString()))
        assertTrue(Files.exists(expectedTarget(selected, connection)))
        assertSavedDirectory(home, connection, selected)
        assertFalse(Files.exists(registrationDestinationFor(root, home, connection)))
    }

    @ParameterizedTest
    @EnumSource(HarnessConnection::class, names = ["PI", "COPILOT", "CODEX_APP_SERVER"])
    fun failedRelocationRestoresConnectionConfigCheckpointAndReceipt(connection: HarnessConnection) {
        val (root, home) = integrationFixture(temporary, connection)
        connectHarness(root, home, connection)
        val old = registrationDestinationFor(root, home, connection)
        val bytes = Files.readAllBytes(old).toList()
        val config = Files.readAllBytes(connectionConfigurationPath(home)).toList()
        val checkpoint = Files.readAllBytes(connectionCheckpointPath(home)).toList()
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        val selected = temporary.resolve("relocation")
        val evidence = mutableListOf<ConnectionRecoveryEvidence>()
        assertThrows<ManagementRejected> {
            connectHarness(
                root,
                home,
                connection,
                directory = admittedConnectionDirectory(selected.toString()),
                recoveryEvidence = { evidence.add(it) },
            ) { _, _ ->
                throw java.io.IOException("case-owned receipt failure")
            }
        }
        assertEquals(bytes, Files.readAllBytes(old).toList())
        assertEquals(config, Files.readAllBytes(connectionConfigurationPath(home)).toList())
        assertEquals(checkpoint, Files.readAllBytes(connectionCheckpointPath(home)).toList())
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
        assertFalse(Files.exists(expectedTarget(selected, connection)))
        assertEquals(listOf(ConnectionRecoveryEvidence.TransactionRestored(connection)), evidence)
    }

    @Test
    fun corruptConfigRecoversCheckpointAndRetainsRejectedBytes() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val selected = temporary.resolve("retained")
        connectHarness(root, home, HarnessConnection.PI, directory = admittedConnectionDirectory(selected.toString()))
        val config = connectionConfigurationPath(home)
        Files.writeString(config, "invalid configuration")
        val evidence = mutableListOf<ConnectionRecoveryEvidence>()
        assertTrue(connectHarness(root, home, HarnessConnection.PI, recoveryEvidence = { evidence.add(it) }))
        assertSavedDirectory(home, HarnessConnection.PI, selected)
        assertEquals(ConnectionRecoveryEvidence.ConfigurationRecovered(connectionCheckpointPath(home)), evidence.last())
        val backup = evidence.filterIsInstance<ConnectionRecoveryEvidence.BackupRetained>().single()
        assertEquals("invalid configuration", Files.readString(backup.backup))
    }

    @Test
    fun failedConfigCommitRestoresAllPriorState() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        connectHarness(root, home, HarnessConnection.PI)
        val config = Files.readAllBytes(connectionConfigurationPath(home)).toList()
        val checkpoint = Files.readAllBytes(connectionCheckpointPath(home)).toList()
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        val selected = temporary.resolve("relocation")
        assertThrows<ManagementRejected> {
            connectHarness(
                root,
                home,
                HarnessConnection.PI,
                directory = admittedConnectionDirectory(selected.toString()),
                commitConfiguration = { path, configuration ->
                    writeConnectionConfiguration(path, configuration)
                    throw java.io.IOException("case-owned config failure")
                },
            )
        }
        assertEquals(config, Files.readAllBytes(connectionConfigurationPath(home)).toList())
        assertEquals(checkpoint, Files.readAllBytes(connectionCheckpointPath(home)).toList())
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
        assertTrue(Files.exists(home.resolve(".pi/agent/extensions/kast.ts")))
        assertFalse(Files.exists(selected.resolve("extensions/kast.ts")))
    }

    @Test
    fun strictConnectionAndInvalidDestinationsRejectBeforeMutation() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val target = home.resolve(".pi/agent/extensions/kast.ts")
        Files.createDirectories(target.parent)
        Files.writeString(target, "preserve")
        assertThrows<ManagementRejected> {
            connectHarness(root, home, HarnessConnection.PI, RegistrationOwnership.REQUIRE_OWNED)
        }
        assertEquals("preserve", Files.readString(target))
        assertFalse(Files.exists(connectionConfigurationPath(home)))
        for (raw in listOf("", "relative", "/tmp/../elsewhere", "/tmp//double", "/tmp/trailing/", "/tmp/control\n")) {
            assertTrue(ConnectionDirectory.admit(raw) is ConnectionDirectoryAdmission.Rejected, raw)
            val parsed = parseManagementCommand(listOf("connect", "pi", "--destination", raw))
            assertTrue(parsed is ManagementParsing.Print && parsed.error, raw)
        }
        assertTrue(
            (parseManagementCommand(listOf("connect", "--destination", "/chosen")) as ManagementParsing.Print).error
        )
        assertTrue(
            (parseManagementCommand(listOf("connect", "pi", "--force", "--no-recover")) as ManagementParsing.Print)
                .error
        )
    }

    private fun expectedTarget(directory: Path, connection: HarnessConnection): Path =
        when (connection) {
            HarnessConnection.PI -> directory.resolve("extensions/kast.ts")
            HarnessConnection.COPILOT -> directory.resolve("extensions/kast/extension.mjs")
            HarnessConnection.CODEX_APP_SERVER -> directory.resolve("kast-codex")
            HarnessConnection.CODEX_MCP -> throw AssertionError("MCP is covered at its process boundary")
        }

    private fun assertSavedDirectory(home: Path, connection: HarnessConnection, directory: Path) {
        val document = Json.parseToJsonElement(Files.readString(connectionConfigurationPath(home))).jsonObject
        assertEquals(setOf("schemaVersion", "connections"), document.keys)
        assertEquals("1", document.getValue("schemaVersion").jsonPrimitive.content)
        val entry = document.getValue("connections").jsonArray.single().jsonObject
        assertEquals(setOf("type", "directory"), entry.keys)
        assertEquals(connection.name, entry.getValue("type").jsonPrimitive.content)
        assertEquals(directory.toString(), entry.getValue("directory").jsonPrimitive.content)
    }
}
