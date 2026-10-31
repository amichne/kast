package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class GuidanceInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `same version with changed cached content invokes provider refresh`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val cache = home.resolve(".codex/plugins/cache/kast/kast/1.2.3")
        Files.writeString(cache.resolve("skills/kast/SKILL.md"), "stale cached content")
        val script =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
                PluginStep(
                    pluginAdd,
                    effect = {
                        marketplace.resolve("plugins/kast").toFile().copyRecursively(cache.toFile(), overwrite = true)
                    },
                ),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(
            PluginInstallOutcome.Installed(PluginHarness.CODEX),
            installCodexPlugin(root, home, script::execute),
        )
        script.assertConsumed()
        assertEquals(
            Files.readString(marketplace.resolve("plugins/kast/skills/kast/SKILL.md")),
            Files.readString(cache.resolve("skills/kast/SKILL.md")),
        )
    }

    @Test
    fun `provider exit without refreshed resources keeps recovery stage`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        Files.writeString(home.resolve(".codex/plugins/cache/kast/kast/1.2.3/skills/kast/SKILL.md"), "stale")
        val script =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
                PluginStep(pluginAdd),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(
            PluginInstallOutcome.RecoveryRequired(
                PluginInstallStage.VERIFICATION,
                PluginInstallFailure.VerificationRejected,
            ),
            installCodexPlugin(root, home, script::execute),
        )
        script.assertConsumed()
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Pi native skill repeats without an MCP extension or Python service`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        assertInstanceOf(
            ProvisionOutcome.Installed::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        val skill = home.resolve(".pi/agent/skills/kast")
        assertTrue(Files.isRegularFile(skill.resolve("SKILL.md")))
        assertTrue(Files.notExists(home.resolve(".pi/agent/extensions/kast.ts")))
        val prior = Files.readString(receiptPath(root))
        assertInstanceOf(
            ProvisionOutcome.AlreadyInstalled::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        assertEquals(prior, Files.readString(receiptPath(root)))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Pi refresh changes exactly owned skill bytes and preserves foreign siblings`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        assertInstanceOf(
            ProvisionOutcome.Installed::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        val sibling = home.resolve(".pi/agent/skills/foreign.md")
        Files.writeString(sibling, "foreign")
        val installation = root.resolve("installation")
        val source = installation.resolve("$CLI_SKILL_RELATIVE_PATH/SKILL.md")
        Files.writeString(source, "selected new guidance")
        val manifest = (readBundledManifest(installation) as BundledManifestRead.Read).manifest
        Files.writeString(
            installation.resolve("installation.json"),
            managementJson.encodeToString(
                manifest.copy(
                    payloadFiles =
                        manifest.payloadFiles.map {
                            if (it.path == "$CLI_SKILL_RELATIVE_PATH/SKILL.md")
                                it.copy(sha256 = "sha256:${sha256(source)}")
                            else it
                        }
                )
            ),
        )
        assertInstanceOf(
            ProvisionOutcome.Installed::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        assertEquals("selected new guidance", Files.readString(home.resolve(".pi/agent/skills/kast/SKILL.md")))
        assertEquals("foreign", Files.readString(sibling))
    }

    @Test
    fun `foreign Pi extension rejects before a skill appears`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val extension = home.resolve(".pi/agent/extensions/kast.ts")
        Files.createDirectories(extension.parent)
        Files.writeString(extension, "foreign extension")
        assertEquals(
            ProvisionOutcome.Rejected(
                ProvisionStage.PROVIDER_INSPECTION,
                ProvisionFailure.PROVIDER_CONFLICT,
                extension.toString(),
            ),
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        assertTrue(Files.notExists(home.resolve(".pi/agent/skills/kast")))
        assertEquals("foreign extension", Files.readString(extension))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Pi preserves unknown files and directories inside a copied skill`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        assertInstanceOf(
            ProvisionOutcome.Installed::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        val destination = home.resolve(".pi/agent/skills/kast")
        val foreign = destination.resolve("foreign.txt")
        Files.writeString(foreign, "foreign data")
        Files.createDirectory(destination.resolve("foreign-directory"))
        assertEquals(
            ProvisionOutcome.Rejected(
                ProvisionStage.RESOURCE_INSPECTION,
                ProvisionFailure.PROVIDER_CONFLICT,
                destination.toString(),
            ),
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        assertEquals("foreign data", Files.readString(foreign))
        assertTrue(Files.isDirectory(destination.resolve("foreign-directory")))
    }
}
