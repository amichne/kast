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

class ProviderRemovalTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `product removal retains payload when a recorded provider namespace conflicts`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val selected = home.resolve("custom-codex")
        retainProviderRegistration(root, ProviderHarness.CODEX, selected)
        val before = admittedReceipt(root)
        val script = PluginScript(PluginStep(marketplaceList, marketplaces(temporary.resolve("foreign"))))
        val rejected =
            org.junit.jupiter.api.assertThrows<ManagementRejected> {
                uninstallInstallation(
                    root,
                    home,
                    environment = mapOf("CODEX_HOME" to "/wrong"),
                    executeInstaller = { _, _ -> error("provider conflict must preserve the installer") },
                    executeHost = { error("provider conflict must preserve the Host") },
                    executeProvider = { environment, arguments ->
                        assertEquals(selected.toString(), environment["CODEX_HOME"])
                        script.execute(arguments)
                    },
                )
            }
        script.assertConsumed()
        assertEquals("uninstall-provider", rejected.stage)
        assertEquals(before, admittedReceipt(root))
        assertTrue(Files.exists(root.resolve("installation/installation.json")))
        assertTrue(Files.notExists(uninstallJournalPath(root)))
    }

    @Test
    fun `product provider cleanup uses recorded namespaces and verifies absent resources`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val selected = home.resolve("custom-codex")
        retainProviderRegistration(root, ProviderHarness.CODEX, selected)
        val script =
            PluginScript(
                PluginStep(marketplaceList, marketplaces()),
                PluginStep(pluginList, plugins()),
                PluginStep(pluginList, plugins()),
                PluginStep(marketplaceList, marketplaces()),
            )
        removeInstallationProviders(root, home, mapOf("CODEX_HOME" to "/wrong")) { environment, arguments ->
            assertEquals(selected.toString(), environment["CODEX_HOME"])
            script.execute(arguments)
        }
        script.assertConsumed()
        assertEquals(emptyList<ProviderRegistration>(), admittedReceipt(root).providers)
        assertTrue(Files.exists(root.resolve("installation/installation.json")))
    }
}

class GuidanceInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Codex uninstall rejects invalid home before provider effects`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val before = Files.readString(receiptPath(root))
        for (invalid in listOf("relative", "/invalid\u0000home", "/tmp/../foreign")) {
            assertEquals(
                PluginObservation.Rejected(PluginInstallFailure.ObservationRejected),
                uninstallHarness(
                    root,
                    home,
                    Harness.CODEX,
                    mapOf("CODEX_HOME" to invalid),
                    execute = { error("Invalid destination must not invoke a provider") },
                    executeLegacyCodexAt = { _, _ -> error("Invalid destination must not remove legacy state") },
                ),
            )
        }
        assertEquals(before, Files.readString(receiptPath(root)))
    }

    @Test
    fun `Codex uninstall verifies provider removal and preserves the product`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val before = Files.readString(receiptPath(root))
        retainProviderRegistration(root, ProviderHarness.CODEX, home.resolve(".codex"))
        val legacyHome = Files.createDirectories(temporary.resolve("legacy-codex")).toRealPath()
        val legacyCommand = root.resolve("installation/bin/kast-mcp-complete")
        recordLegacyMcp(root, legacyCommand, legacyHome)
        val legacy =
            PluginScript(
                PluginStep(
                    listOf("codex", "mcp", "list", "--json"),
                    managementJson.encodeToString(
                        listOf(CodexServer("kast", CodexTransport("stdio", legacyCommand.toString())))
                    ),
                ),
                PluginStep(listOf("codex", "mcp", "remove", "kast")),
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
            )
        val script =
            PluginScript(
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
                PluginStep(listOf("codex", "plugin", "remove", "kast@kast", "--json")),
                PluginStep(pluginList, plugins()),
                PluginStep(listOf("codex", "plugin", "marketplace", "remove", "kast", "--json")),
                PluginStep(marketplaceList, marketplaces()),
            )
        assertEquals(
            PluginObservation.Observed(Unit),
            uninstallHarness(
                root,
                home,
                Harness.CODEX,
                emptyMap(),
                script::execute,
                executeLegacyCodexAt = { selected, arguments ->
                    assertEquals(legacyHome, selected)
                    legacy.execute(arguments)
                },
            ),
        )
        script.assertConsumed()
        legacy.assertConsumed()
        assertEquals(before, Files.readString(receiptPath(root)))
        assertTrue(Files.isDirectory(marketplace))
    }

    private fun recordLegacyMcp(root: Path, command: Path, home: Path) {
        val registration =
            ManagedRegistration(
                HarnessConnection.CODEX_MCP,
                command.toString(),
                sha256(command),
                home.toString(),
            )
        writeManagementReceipt(root, admittedReceipt(root).copy(registrations = listOf(registration)))
    }

    @Test
    fun `foreign Codex marketplace rejects without removal`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        val before = Files.readString(receiptPath(root))
        val script = PluginScript(PluginStep(marketplaceList, marketplaces(temporary.resolve("foreign"))))
        assertEquals(
            PluginObservation.Rejected(PluginInstallFailure.MarketplaceConflict),
            uninstallHarness(root, home, Harness.CODEX, emptyMap(), script::execute),
        )
        script.assertConsumed()
        assertEquals(before, Files.readString(receiptPath(root)))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Pi uninstall preserves changed guidance then resumes exact partial removal`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        assertInstanceOf(
            ProvisionOutcome.Installed::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        val skill = home.resolve(".pi/agent/skills/kast")
        val sibling = Files.writeString(skill.parent.resolve("foreign.md"), "protected")
        val extra = Files.writeString(skill.resolve("extra"), "foreign")
        val before = Files.readString(receiptPath(root))
        assertEquals(
            PluginObservation.Rejected(PluginInstallFailure.PluginConflict),
            uninstallHarness(root, home, Harness.PI, emptyMap(), execute = { error("Pi must not invoke a provider") }),
        )
        assertEquals(before, Files.readString(receiptPath(root)))
        assertEquals("foreign", Files.readString(extra))
        Files.delete(extra)
        Files.delete(skill.resolve("SKILL.md"))
        assertEquals(
            PluginObservation.Observed(Unit),
            uninstallHarness(root, home, Harness.PI, emptyMap(), execute = { error("Pi must not invoke a provider") }),
        )
        assertTrue(Files.notExists(skill))
        assertTrue(Files.notExists(home.resolve(".pi/agent/extensions/kast.ts")))
        assertTrue(admittedReceipt(root).guidance.none { it.harness == Harness.PI })
        assertTrue(Files.isDirectory(root.resolve("installation")))
        assertEquals("protected", Files.readString(sibling))
    }

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
    fun `Pi native skill and preparation hook repeat without MCP or Python`() {
        val root = pluginFixture(temporary)
        val home = temporary.toRealPath().resolve("home")
        assertInstanceOf(
            ProvisionOutcome.Installed::class.java,
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        val skill = home.resolve(".pi/agent/skills/kast")
        assertTrue(Files.isRegularFile(skill.resolve("SKILL.md")))
        assertTrue(Files.readString(home.resolve(".pi/agent/extensions/kast.ts")).contains("hook"))
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
    fun `Pi replaces files and directories inside its recorded skill slot`() {
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
            ProvisionOutcome.Installed(destination.toString()),
            installPiGuidance(root, home, HarnessComposition.CliOnly, emptyMap()),
        )
        assertTrue(Files.notExists(foreign))
        assertTrue(Files.notExists(destination.resolve("foreign-directory")))
    }
}
