package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class ConnectionCodexEntry(val name: String, val transport: ConnectionCodexTransport)

@Serializable private data class ConnectionCodexTransport(val type: String, val command: String)

internal class ConnectionCodexTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun codexEnvironmentSelectsAutomaticRecoveryNamespace() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val directory = temporary.resolve("custom codex")
        Files.createDirectories(directory)
        val config = directory.resolve("config.toml")
        Files.writeString(config, "foreign Kast slot; unrelated settings")
        val command = root.resolve("installation/bin/kast-mcp-complete").toString()
        val script =
            CodexConnectionScript(
                listOf(
                    CodexConnectionCall(directory, listArguments(), listed("foreign")),
                    CodexConnectionCall(directory, addArguments(command), ProcessObservation.Exited(0, "")) {
                        Files.writeString(config, "verified Kast slot; unrelated settings")
                    },
                    CodexConnectionCall(directory, listArguments(), listed(command)),
                )
            )
        val evidence = mutableListOf<ConnectionRecoveryEvidence>()
        assertFalse(
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_MCP,
                environment = mapOf("CODEX_HOME" to directory.toString()),
                executeCodexAt = script::execute,
                recoveryEvidence = { evidence.add(it) },
            )
        )
        val backup = evidence.filterIsInstance<ConnectionRecoveryEvidence.BackupRetained>().single()
        assertEquals(config, backup.destination)
        assertEquals("foreign Kast slot; unrelated settings", Files.readString(backup.backup))
        val saved = readConnectionConfiguration(home) as ConnectionConfigurationRead.Read
        assertEquals(directory, saved.configuration.directory(HarnessConnection.CODEX_MCP)?.path)
        script.assertConsumed()
    }

    @Test
    fun recordedCodexHomeSurvivesPreferenceAndEnvironmentChanges() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val directory = temporary.resolve("custom codex")
        Files.createDirectories(directory)
        val config = directory.resolve("config.toml")
        Files.writeString(config, "owned Kast slot; unrelated settings")
        val launcher = root.resolve("installation/bin/kast-mcp-complete")
        val prior = (readReceipt(root) as ReceiptRead.Read).receipt
        writeManagementReceipt(
            root,
            prior.copy(
                registrations =
                    listOf(
                        ManagedRegistration(
                            HarnessConnection.CODEX_MCP,
                            launcher.toString(),
                            sha256(launcher),
                            directory.toString(),
                        )
                    )
            ),
        )
        val script =
            CodexConnectionScript(
                listOf(
                    CodexConnectionCall(directory, listArguments(), listed(launcher.toString())),
                    CodexConnectionCall(directory, listArguments(), listed(launcher.toString())),
                    CodexConnectionCall(directory, listArguments(), listed(launcher.toString())),
                    CodexConnectionCall(directory, removeArguments(), ProcessObservation.Exited(0, "")) {
                        Files.writeString(config, "unrelated settings")
                    },
                    CodexConnectionCall(directory, listArguments(), listed()),
                )
            )
        assertTrue(
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_MCP,
                environment = mapOf("CODEX_HOME" to temporary.resolve("changed").toString()),
                executeCodexAt = script::execute,
            )
        )
        Files.writeString(connectionConfigurationPath(home), "invalid config")
        Files.writeString(connectionCheckpointPath(home), "invalid checkpoint")
        assertTrue(disconnectHarness(root, home, Harness.CODEX, script::execute))
        assertEquals("unrelated settings", Files.readString(config))
        assertEquals(emptyList<ManagedRegistration>(), (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
        script.assertConsumed()
    }

    @Test
    fun movingCodexRegistersChosenHomeAndRemovesOnlyPreviousOwnedSlot() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val first = home.resolve(".codex")
        val second = temporary.resolve("next codex")
        Files.createDirectories(first)
        Files.createDirectories(second)
        val command = root.resolve("installation/bin/kast-mcp-complete").toString()
        val script =
            CodexConnectionScript(
                listOf(
                    CodexConnectionCall(first, listArguments(), listed()),
                    CodexConnectionCall(first, addArguments(command), ProcessObservation.Exited(0, "")) {
                        Files.writeString(first.resolve("config.toml"), "owned Kast slot; original settings")
                    },
                    CodexConnectionCall(first, listArguments(), listed(command)),
                    CodexConnectionCall(second, listArguments(), listed()),
                    CodexConnectionCall(first, listArguments(), listed(command)),
                    CodexConnectionCall(second, addArguments(command), ProcessObservation.Exited(0, "")) {
                        Files.writeString(second.resolve("config.toml"), "owned Kast slot; next settings")
                    },
                    CodexConnectionCall(second, listArguments(), listed(command)),
                    CodexConnectionCall(first, listArguments(), listed(command)),
                    CodexConnectionCall(first, removeArguments(), ProcessObservation.Exited(0, "")) {
                        Files.writeString(first.resolve("config.toml"), "original settings")
                    },
                    CodexConnectionCall(first, listArguments(), listed()),
                )
            )
        connectHarness(root, home, HarnessConnection.CODEX_MCP, executeCodexAt = script::execute)
        assertFalse(
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_MCP,
                directory = admittedConnectionDirectory(second.toString()),
                executeCodexAt = script::execute,
            )
        )
        assertEquals("original settings", Files.readString(first.resolve("config.toml")))
        assertEquals("owned Kast slot; next settings", Files.readString(second.resolve("config.toml")))
        val saved = readConnectionConfiguration(home) as ConnectionConfigurationRead.Read
        assertEquals(second, saved.configuration.directory(HarnessConnection.CODEX_MCP)?.path)
        script.assertConsumed()
    }

    @Test
    fun boundedProcessBindsSelectedCodexHomeInARealChild() {
        val script = temporary.resolve("observe-home.sh")
        Files.writeString(script, "#!/bin/sh\nprintf '%s' \"$" + "CODEX_HOME\"\n")
        script.toFile().setExecutable(true, false)
        val selected = temporary.resolve("chosen")
        assertEquals(ProcessObservation.Exited(0, selected.toString()), runBounded(listOf(script.toString()), selected))
    }

    private fun listed(command: String? = null): ProcessObservation.Exited =
        ProcessObservation.Exited(
            0,
            managementJson.encodeToString(
                if (command == null) emptyList()
                else listOf(ConnectionCodexEntry("kast", ConnectionCodexTransport("stdio", command)))
            ),
        )

    private fun listArguments() = listOf("codex", "mcp", "list", "--json")

    private fun addArguments(command: String) = listOf("codex", "mcp", "add", "kast", "--", command)

    private fun removeArguments() = listOf("codex", "mcp", "remove", "kast")
}

private data class CodexConnectionCall(
    val directory: Path,
    val arguments: List<String>,
    val observation: ProcessObservation,
    val effect: () -> Unit = {},
)

private class CodexConnectionScript(calls: List<CodexConnectionCall>) {
    private val remaining = ArrayDeque(calls)

    fun execute(directory: Path, arguments: List<String>): ProcessObservation {
        if (remaining.isEmpty()) throw AssertionError("unexpected Codex call")
        val expected = remaining.removeFirst()
        assertEquals(expected.directory, directory)
        assertEquals(expected.arguments, arguments)
        expected.effect()
        return expected.observation
    }

    fun assertConsumed() = assertTrue(remaining.isEmpty(), "unconsumed Codex observations")
}
