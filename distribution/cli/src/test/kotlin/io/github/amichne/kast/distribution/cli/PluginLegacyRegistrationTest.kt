package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class PluginMergedServer(val name: String, val transport: PluginTestMcpServer)

class PluginLegacyRegistrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `plugin removal keeps a different transport from the merged MCP list`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val command = root.resolve("installation/bin/kast-mcp-complete")
        recordLegacyMcp(root, command, home.resolve(".codex"))
        assertEquals(
            PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX),
            cleanupLegacyMcp(root, home, bundledMcpServer()),
        )
        assertEquals(emptyList<ManagedRegistration>(), (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
    }

    @Test
    fun `plugin removal rejects when the exact prior transport remains`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val command = root.resolve("installation/bin/kast-mcp-complete")
        recordLegacyMcp(root, command, home.resolve(".codex"))
        val prior = (readReceipt(root) as ReceiptRead.Read).receipt
        assertEquals(
            PluginInstallOutcome.RecoveryRequired(
                PluginInstallStage.LEGACY_REGISTRATION_REMOVAL,
                PluginInstallFailure.LegacyRegistrationRejected,
            ),
            cleanupLegacyMcp(root, home, CodexServer("kast", CodexTransport("stdio", command.toString()))),
        )
        assertEquals(prior.registrations, (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
    }

    private fun cleanupLegacyMcp(root: Path, home: Path, remaining: CodexServer): PluginInstallOutcome {
        val command = root.resolve("installation/bin/kast-mcp-complete")
        val manual = mcpServers(CodexServer("kast", CodexTransport("stdio", command.toString())))
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val provider =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), manual),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        val legacy =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), manual),
                PluginStep(listOf("codex", "mcp", "remove", "kast")),
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers(remaining)),
            )
        val outcome =
            installCodexPlugin(
                root,
                home,
                provider::execute,
                executeLegacyCodexAt = { _, arguments -> legacy.execute(arguments) },
            )
        provider.assertConsumed()
        legacy.assertConsumed()
        return outcome
    }

    @Test
    fun `foreign environment and working directory reject even with the same Bash command`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val expected = bundledMcpServer().transport
        val transport = PluginTestMcpServer(expected.command, expected.args)
        for (foreign in
            listOf(
                transport.copy(env = mapOf("HOME" to "/foreign")),
                transport.copy(envVars = listOf("KAST_INSTALL_ROOT")),
                transport.copy(cwd = "/foreign"),
            )) {
            val calls = mutableListOf<List<String>>()
            val outcome =
                installCodexPlugin(
                    root,
                    temporary.toRealPath().resolve("home"),
                    executeCodex = { arguments ->
                        calls += arguments
                        org.junit.jupiter.api.Assertions.assertTrue(calls.size <= 3)
                        ProcessObservation.Exited(
                            0,
                            when (arguments) {
                                listOf("codex", "mcp", "list", "--json") ->
                                    managementJson.encodeToString(listOf(PluginMergedServer("kast", foreign)))
                                marketplaceList -> marketplaces(marketplace)
                                pluginList -> plugins(plugin(marketplace))
                                else -> error("unexpected Codex effect: $arguments")
                            },
                        )
                    },
                )
            assertEquals(
                PluginInstallOutcome.Rejected(
                    PluginInstallStage.LEGACY_REGISTRATION_INSPECTION,
                    PluginInstallFailure.LegacyRegistrationRejected,
                ),
                outcome,
            )
            assertEquals(listOf(listOf("codex", "mcp", "list", "--json")), calls)
        }
    }

    @Test
    fun `a Bash command must match the admitted plugin transport exactly`() {
        val root = pluginFixture(temporary)
        val transport = bundledMcpServer().transport
        for (foreign in
            listOf(
                transport.copy(command = "/foreign/bash"),
                transport.copy(args = transport.args + "extra"),
                transport.copy(args = listOf("-c", "exec /foreign/kast")),
                transport.copy(type = "streamable_http"),
            )) {
            val executor =
                PluginScript(
                    PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers(CodexServer("kast", foreign)))
                )
            assertEquals(
                PluginInstallOutcome.Rejected(
                    PluginInstallStage.LEGACY_REGISTRATION_INSPECTION,
                    PluginInstallFailure.LegacyRegistrationRejected,
                ),
                installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
            )
            executor.assertConsumed()
        }
    }

    @Test
    fun `foreign standalone MCP registration rejects before plugin effects`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val calls = mutableListOf<List<String>>()
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        val outcome =
            installCodexPlugin(
                root,
                home,
                executeCodex = { arguments ->
                    calls += arguments
                    when (arguments) {
                        listOf("codex", "mcp", "list", "--json") ->
                            ProcessObservation.Exited(
                                0,
                                Json.encodeToString(
                                    listOf(CodexServer("kast", CodexTransport("stdio", "/foreign/kast")))
                                ),
                            )
                        marketplaceList -> ProcessObservation.Exited(0, marketplaces(marketplace))
                        pluginList -> ProcessObservation.Exited(0, plugins(plugin(marketplace)))
                        else -> throw AssertionError("unexpected Codex mutation: $arguments")
                    }
                },
            )

        org.junit.jupiter.api.Assertions.assertTrue(outcome is PluginInstallOutcome.Rejected)
        assertEquals("LEGACY_REGISTRATION_INSPECTION", (outcome as PluginInstallOutcome.Rejected).stage.name)
        assertEquals(listOf(listOf("codex", "mcp", "list", "--json")), calls)
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
    }

    @Test
    fun `plugin success retires the receipt-owned App Server launcher`() {
        val root = pluginFixture(temporary)
        val launcher = legacyAppServerRegistration(root, temporary.toRealPath().resolve("home"))
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )

        assertEquals(
            PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX),
            installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
        )

        executor.assertConsumed()
        assertFalse(Files.exists(launcher))
        assertEquals(emptyList<ManagedRegistration>(), (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
    }

    @Test
    fun `plugin success removes the exact legacy MCP registration from its recorded namespace`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val codexHome = Files.createDirectories(temporary.resolve("legacy-codex")).toRealPath()
        val command = root.resolve("installation/bin/kast-mcp-complete")
        recordLegacyMcp(root, command, codexHome)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        marketplace
            .resolve("plugins/kast")
            .toFile()
            .copyRecursively(codexHome.resolve("plugins/cache/kast/kast/1.2.3").toFile(), overwrite = true)
        val executor =
            PluginScript(
                PluginStep(
                    listOf("codex", "mcp", "list", "--json"),
                    Json.encodeToString(listOf(CodexServer("kast", CodexTransport("stdio", command.toString())))),
                ),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        val legacy =
            PluginScript(
                PluginStep(
                    listOf("codex", "mcp", "list", "--json"),
                    Json.encodeToString(listOf(CodexServer("kast", CodexTransport("stdio", command.toString())))),
                ),
                PluginStep(listOf("codex", "mcp", "remove", "kast")),
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
            )

        assertEquals(
            PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX),
            installCodexPlugin(
                root,
                home,
                executor::execute,
                codexHome = codexHome,
                executeLegacyCodexAt = { directory, arguments ->
                    assertEquals(codexHome, directory)
                    legacy.execute(arguments)
                },
            ),
        )

        executor.assertConsumed()
        legacy.assertConsumed()
        assertEquals(emptyList<ManagedRegistration>(), (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
    }

    @Test
    fun `plugin reports legacy repair when a foreign launcher replaces the recorded bytes`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val launcher = legacyAppServerRegistration(root, home)
        Files.writeString(launcher, "foreign replacement")
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        val executor = PluginScript(PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()))

        assertEquals(
            PluginInstallOutcome.Rejected(
                PluginInstallStage.LEGACY_REGISTRATION_INSPECTION,
                PluginInstallFailure.LegacyRegistrationRejected,
            ),
            installCodexPlugin(root, home, executor::execute),
        )

        executor.assertConsumed()
        assertEquals("foreign replacement", Files.readString(launcher))
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
    }

    private fun recordLegacyMcp(root: Path, command: Path, codexHome: Path) {
        val receipt = (readReceipt(root) as ReceiptRead.Read).receipt
        writeManagementReceipt(
            root,
            receipt.copy(
                registrations =
                    listOf(
                        ManagedRegistration(
                            HarnessConnection.CODEX_MCP,
                            command.toString(),
                            sha256(command),
                            codexHome.toString(),
                        )
                    )
            ),
        )
    }
}
