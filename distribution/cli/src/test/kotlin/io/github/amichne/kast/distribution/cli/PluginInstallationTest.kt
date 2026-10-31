package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh installation verifies marketplace and installed enabled plugin without a new receipt`() {
        val root = pluginFixture(temporary)
        val before = Files.readAllBytes(receiptPath(root)).toList()
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces()),
                PluginStep(pluginList, plugins()),
                PluginStep(listOf("codex", "plugin", "marketplace", "add", marketplace.toString(), "--json")),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginAdd),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(
            PluginInstallOutcome.Installed(PluginHarness.CODEX),
            installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
        )
        executor.assertConsumed()
        assertEquals(before, Files.readAllBytes(receiptPath(root)).toList())
    }

    @Test
    fun `installed plugin repeats with inspection only at its single payload location`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH").toRealPath()
        val executor =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers(bundledMcpServer())),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(
            PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX),
            installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
        )
        executor.assertConsumed()
    }

    @Test
    fun `foreign and duplicate marketplaces reject before any installation`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val foreign = temporary.resolve("foreign")
        val mcpAbsent = PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers())
        listOf(marketplaces(foreign), marketplaces(marketplace, marketplace)).forEach { observation ->
            val executor = PluginScript(mcpAbsent, PluginStep(marketplaceList, observation))
            assertEquals(
                PluginInstallOutcome.Rejected(
                    PluginInstallStage.MARKETPLACE_INSPECTION,
                    PluginInstallFailure.MarketplaceConflict,
                ),
                installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
            )
            executor.assertConsumed()
        }
    }

    @Test
    fun `foreign and duplicate plugin identities reject without replacement`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        listOf(plugins(plugin(temporary.resolve("foreign"))), plugins(plugin(marketplace), plugin(marketplace)))
            .forEach { observation ->
                val executor =
                    PluginScript(
                        PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                        PluginStep(marketplaceList, marketplaces(marketplace)),
                        PluginStep(pluginList, observation),
                    )
                assertEquals(
                    PluginInstallOutcome.Rejected(
                        PluginInstallStage.PLUGIN_INSPECTION,
                        PluginInstallFailure.PluginConflict,
                    ),
                    installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
                )
                executor.assertConsumed()
            }
    }

    @Test
    fun `malformed observations and process failures retain their finite stage and cause`() {
        val root = pluginFixture(temporary)
        val cases =
            listOf(
                ProcessObservation.Exited(0, "malformed observation") to PluginInstallFailure.ObservationRejected,
                ProcessObservation.Exited(17, "") to PluginInstallFailure.ProcessExited(17),
                ProcessObservation.Unavailable to PluginInstallFailure.ProcessUnavailable,
            )
        cases.forEach { (observation, failure) ->
            val executor =
                PluginScript(
                    PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                    PluginStep(marketplaceList, observation = observation),
                )
            assertEquals(
                PluginInstallOutcome.Rejected(PluginInstallStage.MARKETPLACE_INSPECTION, failure),
                installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
            )
            executor.assertConsumed()
        }
    }

    @Test
    fun `changed or unmanifested bundled files reject before Codex runs`() {
        val root = pluginFixture(temporary)
        val context = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH/plugins/kast/references/onboarding.md")
        Files.writeString(context, "changed context")
        assertPayloadRejection(root)
        Files.writeString(
            context,
            "Use the installed Kast tools.\n",
        )
        Files.writeString(context.resolveSibling("unmanifested.md"), "unverified content")
        assertPayloadRejection(root)
    }

    @Test
    fun `missing required context and symlink bundle reject before Codex runs`() {
        val root = pluginFixture(temporary)
        val bundle = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val context = bundle.resolve("plugins/kast/references/onboarding.md")
        Files.delete(context)
        assertPayloadRejection(root)
        val retained = bundle.resolveSibling("retained-agent-tools")
        Files.move(bundle, retained)
        Files.createSymbolicLink(bundle, retained)
        assertPayloadRejection(root)
    }

    @Test
    fun `CLI guidance admission does not need an MCP launcher`() {
        val root = pluginFixture(temporary)
        Files.writeString(root.resolve("installation/bin/kast-mcp-complete"), "foreign executable")
        assertInstanceOf(PluginObservation.Observed::class.java, AdmittedPluginBundle.admit(root))
    }

    @Test
    fun `disabled or older owned plugin is installed again and verified at the bundled version`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers(bundledMcpServer())),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins(plugin(marketplace).copy(enabled = false, version = "1.2.2"))),
                PluginStep(pluginAdd),
                PluginStep(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(
            PluginInstallOutcome.Installed(PluginHarness.CODEX),
            installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
        )
        executor.assertConsumed()
    }

    @Test
    fun `a successful install process with absent plugin is an unverified outcome`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins()),
                PluginStep(pluginAdd),
                PluginStep(pluginList, plugins()),
            )
        assertEquals(
            PluginInstallOutcome.RecoveryRequired(
                PluginInstallStage.VERIFICATION,
                PluginInstallFailure.VerificationRejected,
            ),
            installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
        )
        executor.assertConsumed()
    }

    @Test
    fun `plugin failure leaves the host owned marketplace and reports installation stage`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                PluginStep(listOf("codex", "mcp", "list", "--json"), mcpServers()),
                PluginStep(marketplaceList, marketplaces(marketplace)),
                PluginStep(pluginList, plugins()),
                PluginStep(pluginAdd, observation = ProcessObservation.Exited(23, "")),
            )
        assertEquals(
            PluginInstallOutcome.RecoveryRequired(
                PluginInstallStage.PLUGIN_INSTALLATION,
                PluginInstallFailure.ProcessExited(23),
            ),
            installCodexPlugin(root, temporary.toRealPath().resolve("home"), executor::execute),
        )
        executor.assertConsumed()
    }

    @Test
    fun `encoded outcome retains uppercase discriminators and process exit data`() {
        listOf(
                PluginInstallOutcome.Installed(PluginHarness.CODEX) to "INSTALLED",
                PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX) to "ALREADY_INSTALLED",
            )
            .forEach { (outcome, expectedType) ->
                val document = Json.parseToJsonElement(outcome.asJson()).jsonObject
                assertEquals(setOf("type", "harness"), document.keys)
                assertEquals(expectedType, document.getValue("type").jsonPrimitive.content)
                assertEquals("CODEX", document.getValue("harness").jsonPrimitive.content)
            }
        val document =
            Json.parseToJsonElement(
                    PluginInstallOutcome.Rejected(
                            PluginInstallStage.PLUGIN_INSTALLATION,
                            PluginInstallFailure.ProcessExited(23),
                        )
                        .asJson()
                )
                .jsonObject
        assertEquals(setOf("type", "stage", "failure"), document.keys)
        assertEquals("REJECTED", document.getValue("type").jsonPrimitive.content)
        assertEquals("PLUGIN_INSTALLATION", document.getValue("stage").jsonPrimitive.content)
        val failure = document.getValue("failure").jsonObject
        assertEquals(setOf("type", "exitCode"), failure.keys)
        assertEquals("PROCESS_EXITED", failure.getValue("type").jsonPrimitive.content)
        assertEquals("23", failure.getValue("exitCode").jsonPrimitive.content)
    }

    @Test
    fun `finite failures encode an explicit discriminator without optional case data`() {
        listOf(
                PluginInstallFailure.InstallationLockUnavailable to "INSTALLATION_LOCK_UNAVAILABLE",
                PluginInstallFailure.InstallationUnavailable to "INSTALLATION_UNAVAILABLE",
                PluginInstallFailure.PayloadRejected to "PAYLOAD_REJECTED",
                PluginInstallFailure.ProcessUnavailable to "PROCESS_UNAVAILABLE",
                PluginInstallFailure.ObservationRejected to "OBSERVATION_REJECTED",
                PluginInstallFailure.MarketplaceConflict to "MARKETPLACE_CONFLICT",
                PluginInstallFailure.PluginConflict to "PLUGIN_CONFLICT",
                PluginInstallFailure.VerificationRejected to "VERIFICATION_REJECTED",
                PluginInstallFailure.LegacyRegistrationRejected to "LEGACY_REGISTRATION_REJECTED",
            )
            .forEach { (failure, expectedType) ->
                val document =
                    Json.parseToJsonElement(
                            PluginInstallOutcome.Rejected(PluginInstallStage.VERIFICATION, failure).asJson()
                        )
                        .jsonObject
                        .getValue("failure")
                        .jsonObject
                assertEquals(setOf("type"), document.keys)
                assertEquals(expectedType, document.getValue("type").jsonPrimitive.content)
            }
    }

    private fun assertPayloadRejection(root: Path) {
        assertEquals(
            PluginInstallOutcome.Rejected(PluginInstallStage.PAYLOAD_ADMISSION, PluginInstallFailure.PayloadRejected),
            installCodexPlugin(
                root,
                temporary.toRealPath().resolve("home"),
                executeCodex = { throw AssertionError("Codex must not run for unverified payloads") },
            ),
        )
    }
}
