package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HarnessCompositionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Codex composition transitions replace only the admitted marketplace and refresh cache bytes`() {
        val root = pluginFixtureWithMcp(temporary)
        val home = temporary.toRealPath().resolve("home")
        val primary = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val optional = primary.resolve("compositions/mcp")
        val cache = home.resolve(".codex/plugins/cache/kast/kast/1.2.3")
        val before = Files.readString(receiptPath(root))
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
                        assertEquals(
                            listOf(
                                "uv",
                                "sync",
                                "--locked",
                                "--script",
                                optional.resolve("plugins/kast/skills/kast/scripts/server.py").toString(),
                            ),
                            arguments,
                        )
                        assertEquals(mapOf("HOME" to home.toString()), environment)
                        dependencyCalls += 1
                        ProcessObservation.Exited(0, "")
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
        assertEquals(before, Files.readString(receiptPath(root)))
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
                PluginInstallFailure.ProcessExited(23),
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
