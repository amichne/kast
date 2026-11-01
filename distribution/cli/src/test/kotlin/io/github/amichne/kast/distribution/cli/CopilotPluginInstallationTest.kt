package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable
private data class CopilotTestMarketplace(val name: String, val source: String, val isDefault: Boolean = false)

@Serializable
private data class CopilotTestPlugin(
    val name: String = "kast",
    val marketplace: String = "kast",
    val version: String = "1.2.3",
    val enabled: Boolean = true,
    val source: String = "live",
    val installedFrom: String,
)

class CopilotPluginInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh Copilot setup uses its plugin manager and the admitted local source`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val bundle = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val script =
            PluginScript(
                PluginStep(marketplacesCommand, markets()),
                PluginStep(pluginsCommand, installed()),
                PluginStep(listOf("copilot", "plugin", "marketplace", "add", bundle.toString())),
                PluginStep(marketplacesCommand, markets(bundle)),
                PluginStep(listOf("copilot", "plugin", "install", "kast@kast")),
                PluginStep(pluginsCommand, installed(bundle)),
            )
        assertEquals(
            ProvisionOutcome.Installed(bundle.resolve("plugins/kast").toString()),
            installCopilotGuidance(root, home, HarnessComposition.CliOnly, emptyMap(), execution(script)),
        )
        script.assertConsumed()
    }

    @Test
    fun `Copilot repeats from actual live resources without a provider write`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val bundle = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val script =
            PluginScript(
                PluginStep(marketplacesCommand, markets(bundle)),
                PluginStep(pluginsCommand, installed(bundle)),
            )
        assertEquals(
            ProvisionOutcome.AlreadyInstalled(bundle.resolve("plugins/kast").toString()),
            installCopilotGuidance(root, home, HarnessComposition.CliOnly, emptyMap(), execution(script)),
        )
        script.assertConsumed()
    }

    @Test
    fun `foreign Copilot marketplace rejects before provider writes`() {
        val root = pluginFixture(temporary)
        val script = PluginScript(PluginStep(marketplacesCommand, markets(temporary.resolve("foreign"))))
        assertEquals(
            ProvisionOutcome.Rejected(
                ProvisionStage.MARKETPLACE_INSPECTION,
                ProvisionFailure.PROVIDER_CONFLICT,
                "copilot",
            ),
            installCopilotGuidance(
                root,
                temporary.toRealPath().resolve("home"),
                HarnessComposition.CliOnly,
                emptyMap(),
                execution(script),
            ),
        )
        script.assertConsumed()
    }

    @Test
    fun `Copilot provider failure after registration keeps the exact incomplete stage`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val bundle = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val script =
            PluginScript(
                PluginStep(marketplacesCommand, markets()),
                PluginStep(pluginsCommand, installed()),
                PluginStep(listOf("copilot", "plugin", "marketplace", "add", bundle.toString())),
                PluginStep(marketplacesCommand, markets(bundle)),
                PluginStep(
                    listOf("copilot", "plugin", "install", "kast@kast"),
                    observation = ProcessObservation.Exited(23, ""),
                ),
            )
        assertInstanceOf(
            ProvisionOutcome.RecoveryRequired::class.java,
            installCopilotGuidance(root, home, HarnessComposition.CliOnly, emptyMap(), execution(script)),
        )
        script.assertConsumed()
    }

    @Test
    fun `Copilot CLI-only transition removes exactly the owned MCP composition`() {
        val root = pluginFixtureWithMcp(temporary)
        val home = temporary.toRealPath().resolve("home")
        val primary = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val optional = primary.resolve("compositions/mcp")
        val script =
            PluginScript(
                PluginStep(marketplacesCommand, markets(optional)),
                PluginStep(pluginsCommand, installed(optional)),
                PluginStep(listOf("copilot", "plugin", "uninstall", "kast@kast")),
                PluginStep(listOf("copilot", "plugin", "marketplace", "remove", "kast")),
                PluginStep(listOf("copilot", "plugin", "marketplace", "add", primary.toString())),
                PluginStep(marketplacesCommand, markets(primary)),
                PluginStep(listOf("copilot", "plugin", "install", "kast@kast")),
                PluginStep(pluginsCommand, installed(primary)),
            )
        assertEquals(
            ProvisionOutcome.Installed(primary.resolve("plugins/kast").toString()),
            installCopilotGuidance(root, home, HarnessComposition.CliOnly, emptyMap(), execution(script)),
        )
        script.assertConsumed()
    }

    private fun execution(script: PluginScript) =
        GuidanceProviderExecution(script::execute) { _, _ -> throw AssertionError("CLI-only setup must not start uv") }

    private val marketplacesCommand = listOf("copilot", "plugin", "marketplace", "list", "--json")
    private val pluginsCommand = listOf("copilot", "plugin", "list", "--json")

    private fun markets(vararg paths: Path) =
        managementJson.encodeToString(paths.map { CopilotTestMarketplace("kast", "Local: $it") })

    private fun installed(vararg paths: Path) =
        managementJson.encodeToString(paths.map { CopilotTestPlugin(installedFrom = it.toString()) })
}
