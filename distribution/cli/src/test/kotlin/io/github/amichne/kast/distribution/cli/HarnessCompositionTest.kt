package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Serializable
private data class McpPreparedFixture(
    val installationRoot: String,
    val environmentRoot: String,
    val disposition: String,
    val status: String = "MCP_PREPARED",
)

@Serializable private data class McpRejectedFixture(val failure: String, val status: String = "MCP_REJECTED")

class HarnessCompositionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Codex composition transitions replace only the admitted marketplace and refresh cache bytes`() {
        val root = pluginFixtureWithMcp(temporary)
        val home = temporary.toRealPath().resolve("home")
        val primary = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val optional = primary.resolve("compositions/mcp")
        val cache = home.resolve(".codex/plugins/cache/kast/kast/1.2.3")
        val before = admittedReceipt(root)
        for ((composition, prior, selected) in
            listOf(
                Triple(HarnessComposition.CliWithMcp, primary, optional),
                Triple(HarnessComposition.CliOnly, optional, primary),
            )) {
            val script = transitionScript(prior, selected, cache)
            var dependencyCalls = 0
            assertEquals(
                PluginInstallOutcome.Installed(PluginHarness.CODEX),
                installCodexPlugin(
                    root,
                    home,
                    script::execute,
                    composition = composition,
                    executeDependency = { arguments, environment ->
                        assertEquals(HarnessComposition.CliWithMcp, composition)
                        assertPrivatePreparation(root, home, arguments, environment)
                        dependencyCalls += 1
                        prepared(root)
                    },
                ),
            )
            script.assertConsumed()
            assertEquals(if (composition == HarnessComposition.CliWithMcp) 1 else 0, dependencyCalls)
            assertTrue(
                (AdmittedPluginBundle.admit(root, composition) as PluginObservation.Observed)
                    .value
                    .resourcesMatch(cache)
            )
        }
        assertEquals(
            before.copy(
                providers = listOf(ProviderRegistration(ProviderHarness.CODEX, home.resolve(".codex").toString()))
            ),
            admittedReceipt(root),
        )
    }

    @Test
    fun `dependency failure rejects optional MCP before provider changes`() {
        val root = pluginFixtureWithMcp(temporary)
        val home = temporary.toRealPath().resolve("home")
        val primary = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val script =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces(primary)),
                PluginStep(pluginList, plugins(plugin(primary))),
            )
        assertEquals(
            PluginInstallOutcome.Rejected(
                PluginInstallStage.DEPENDENCY_PREPARATION,
                PluginInstallFailure.ObservationRejected,
            ),
            installCodexPlugin(
                root,
                home,
                script::execute,
                composition = HarnessComposition.CliWithMcp,
                executeDependency = { _, _ -> ProcessObservation.Exited(23, "") },
            ),
        )
        script.assertConsumed()
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "MCP_INSTALLATION_REJECTED",
                "MCP_STATE_REJECTED",
                "MCP_ENVIRONMENT_MISSING",
                "MCP_PREPARATION_INCOMPLETE",
                "MCP_WHEEL_INSTALL_FAILED",
                "MCP_IMPORT_CHECK_FAILED",
                "MCP_PROCESS_TIMEOUT",
                "MCP_FILESYSTEM_REJECTED",
                "MCP_PROCESS_UNAVAILABLE",
                "MCP_CONCURRENT_CHANGE",
            ]
    )
    fun `dependency failures retain their public reason`(reason: String) {
        val root = pluginFixtureWithMcp(temporary)
        val bundle =
            (AdmittedPluginBundle.admit(root, HarnessComposition.CliWithMcp) as PluginObservation.Observed).value
        val result =
            prepareOptionalMcp(bundle, temporary) { _, _ ->
                ProcessObservation.Exited(1, managementJson.encodeToString(McpRejectedFixture(reason)))
            }
                as PluginObservation.Rejected
        assertEquals(reason, pluginFailure(result.failure).name)
    }

    @Test
    fun `dependency success requires matching installation evidence and known disposition`() {
        val root = pluginFixtureWithMcp(temporary)
        val bundle =
            (AdmittedPluginBundle.admit(root, HarnessComposition.CliWithMcp) as PluginObservation.Observed).value
        for (observation in
            listOf(
                prepared(root.resolve("foreign")),
                prepared(root, "UNKNOWN"),
                ProcessObservation.Exited(0, managementJson.encodeToString(McpRejectedFixture("MCP_STATE_REJECTED"))),
                ProcessObservation.Exited(1, managementJson.encodeToString(McpRejectedFixture("UNKNOWN"))),
            )) {
            assertEquals(
                PluginObservation.Rejected(PluginInstallFailure.ObservationRejected),
                prepareOptionalMcp(bundle, temporary) { _, _ -> observation },
            )
        }
        for (disposition in listOf("CREATED", "REUSED")) {
            assertEquals(
                PluginObservation.Observed(Unit),
                prepareOptionalMcp(bundle, temporary) { _, _ -> prepared(root, disposition) },
            )
        }
    }

    private fun prepared(root: Path, disposition: String = "CREATED"): ProcessObservation =
        ProcessObservation.Exited(
            0,
            managementJson.encodeToString(
                McpPreparedFixture(
                    root.resolve("installation").toString(),
                    root.resolve("installation/state/mcp").toString(),
                    disposition,
                )
            ),
        )

    @Test
    fun `MCP admission rejects changed interpreter imports before executing any provider`() {
        val root = pluginFixtureWithMcp(temporary)
        val home = temporary.toRealPath().resolve("home")
        Files.writeString(root.resolve("installation/share/kast/private-python/lib/python3.12/site.py"), "changed")
        assertEquals(
            PluginInstallOutcome.Rejected(PluginInstallStage.PAYLOAD_ADMISSION, PluginInstallFailure.PayloadRejected),
            installCodexPlugin(
                root,
                home,
                executeCodex = { error("provider must not run") },
                composition = HarnessComposition.CliWithMcp,
                executeDependency = { _, _ -> error("unadmitted interpreter must not run") },
            ),
        )
        assertTrue(AdmittedPluginBundle.admit(root, HarnessComposition.CliOnly) is PluginObservation.Observed)
    }

    private fun assertPrivatePreparation(
        root: Path,
        home: Path,
        arguments: List<String>,
        environment: Map<String, String>,
    ) {
        assertEquals(
            listOf(
                root.resolve("installation/share/kast/private-python/bin/python3.12").toString(),
                "-I",
                "-S",
                "-B",
                root.resolve("installation/share/kast/mcp_environment.py").toString(),
                "prepare",
                "--install-root",
                root.resolve("installation").toString(),
            ),
            arguments,
        )
        assertEquals(mapOf("HOME" to home.toString()), environment)
    }

    private fun transitionScript(prior: Path, selected: Path, cache: Path): PluginScript =
        PluginScript(
            PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
            PluginStep(marketplaceList, marketplaces(prior)),
            PluginStep(pluginList, plugins(plugin(prior))),
            PluginStep(listOf("codex", "plugin", "marketplace", "remove", "kast", "--json")),
            PluginStep(listOf("codex", "plugin", "marketplace", "add", selected.toString(), "--json")),
            PluginStep(marketplaceList, marketplaces(selected)),
            PluginStep(pluginAdd, effect = { copyPluginCache(selected, cache) }),
            PluginStep(pluginList, plugins(plugin(selected))),
        )
}
