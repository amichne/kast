package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class PluginTestManifest(val name: String, val version: String)

@Serializable private data class PluginTestMarketplace(val name: String, val plugins: List<PluginTestMarketplaceEntry>)

@Serializable private data class PluginTestMarketplaceEntry(val name: String, val source: PluginTestSource)

@Serializable private data class PluginTestSource(val source: String, val path: String)

@Serializable private data class PluginTestMcp(val mcpServers: PluginTestMcpServers)

@Serializable private data class PluginTestMcpServers(val kast: PluginTestMcpServer)

@Serializable private data class PluginTestMcpServer(val command: String, val args: List<String>)

@Serializable private data class PluginTestMarketplaces(val marketplaces: List<PluginTestMarketplaceObservation>)

@Serializable
private data class PluginTestMarketplaceObservation(
    val name: String,
    val root: String,
    val marketplaceSource: PluginTestMarketplaceSource,
)

@Serializable private data class PluginTestMarketplaceSource(val sourceType: String, val source: String)

@Serializable private data class PluginTestPlugins(val installed: List<PluginTestPluginObservation>)

@Serializable
private data class PluginTestPluginObservation(
    val pluginId: String,
    val name: String,
    val marketplaceName: String,
    val version: String,
    val installed: Boolean,
    val enabled: Boolean,
    val source: PluginTestSource,
    val marketplaceSource: PluginTestMarketplaceSource,
)

class PluginInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh installation verifies marketplace and installed enabled plugin without a new receipt`() {
        val root = pluginFixture(temporary)
        val before = Files.readAllBytes(receiptPath(root)).toList()
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                Step(marketplaceList, marketplaces()),
                Step(pluginList, plugins()),
                Step(listOf("codex", "plugin", "marketplace", "add", marketplace.toString(), "--json")),
                Step(marketplaceList, marketplaces(marketplace)),
                Step(pluginAdd),
                Step(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(PluginInstallOutcome.Installed(PluginHarness.CODEX), installCodexPlugin(root, executor::execute))
        executor.assertConsumed()
        assertEquals(before, Files.readAllBytes(receiptPath(root)).toList())
    }

    @Test
    fun `installed plugin repeats with inspection only at its single payload location`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH").toRealPath()
        val executor =
            PluginScript(
                Step(marketplaceList, marketplaces(marketplace)),
                Step(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(
            PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX),
            installCodexPlugin(root, executor::execute),
        )
        executor.assertConsumed()
    }

    @Test
    fun `foreign and duplicate marketplaces reject before any installation`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val foreign = temporary.resolve("foreign")
        listOf(marketplaces(foreign), marketplaces(marketplace, marketplace)).forEach { observation ->
            val executor = PluginScript(Step(marketplaceList, observation))
            assertEquals(
                PluginInstallOutcome.Rejected(
                    PluginInstallStage.MARKETPLACE_INSPECTION,
                    PluginInstallFailure.MarketplaceConflict,
                ),
                installCodexPlugin(root, executor::execute),
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
                    PluginScript(Step(marketplaceList, marketplaces(marketplace)), Step(pluginList, observation))
                assertEquals(
                    PluginInstallOutcome.Rejected(
                        PluginInstallStage.PLUGIN_INSPECTION,
                        PluginInstallFailure.PluginConflict,
                    ),
                    installCodexPlugin(root, executor::execute),
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
            val executor = PluginScript(Step(marketplaceList, observation = observation))
            assertEquals(
                PluginInstallOutcome.Rejected(PluginInstallStage.MARKETPLACE_INSPECTION, failure),
                installCodexPlugin(root, executor::execute),
            )
            executor.assertConsumed()
        }
    }

    @Test
    fun `changed or unmanifested bundled files reject before Codex runs`() {
        val root = pluginFixture(temporary)
        val skill = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH/plugins/kast/skills/kast/SKILL.md")
        Files.writeString(skill, "changed skill")
        assertPayloadRejection(root)
        Files.writeString(
            skill,
            "---\nname: kast\ndescription: Query Kotlin with Kast.\n---\nUse the installed Kast tools.\n",
        )
        Files.writeString(skill.resolveSibling("unmanifested.md"), "unverified content")
        assertPayloadRejection(root)
    }

    @Test
    fun `missing required skill and symlink bundle reject before Codex runs`() {
        val root = pluginFixture(temporary)
        val bundle = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val skill = bundle.resolve("plugins/kast/skills/kast/SKILL.md")
        Files.delete(skill)
        assertPayloadRejection(root)
        val retained = bundle.resolveSibling("retained-agent-tools")
        Files.move(bundle, retained)
        Files.createSymbolicLink(bundle, retained)
        assertPayloadRejection(root)
    }

    @Test
    fun `changed MCP launcher rejects before plugin registration`() {
        val root = pluginFixture(temporary)
        Files.writeString(root.resolve("installation/bin/kast-mcp-complete"), "foreign executable")
        assertPayloadRejection(root)
    }

    @Test
    fun `disabled or older owned plugin is installed again and verified at the bundled version`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                Step(marketplaceList, marketplaces(marketplace)),
                Step(pluginList, plugins(plugin(marketplace).copy(enabled = false, version = "1.2.2"))),
                Step(pluginAdd),
                Step(pluginList, plugins(plugin(marketplace))),
            )
        assertEquals(PluginInstallOutcome.Installed(PluginHarness.CODEX), installCodexPlugin(root, executor::execute))
        executor.assertConsumed()
    }

    @Test
    fun `a successful install process with absent plugin is an unverified outcome`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                Step(marketplaceList, marketplaces(marketplace)),
                Step(pluginList, plugins()),
                Step(pluginAdd),
                Step(pluginList, plugins()),
            )
        assertEquals(
            PluginInstallOutcome.Rejected(PluginInstallStage.VERIFICATION, PluginInstallFailure.VerificationRejected),
            installCodexPlugin(root, executor::execute),
        )
        executor.assertConsumed()
    }

    @Test
    fun `plugin failure leaves the host owned marketplace and reports installation stage`() {
        val root = pluginFixture(temporary)
        val marketplace = root.resolve("installation/$AGENT_TOOLS_RELATIVE_PATH")
        val executor =
            PluginScript(
                Step(marketplaceList, marketplaces(marketplace)),
                Step(pluginList, plugins()),
                Step(pluginAdd, observation = ProcessObservation.Exited(23, "")),
            )
        assertEquals(
            PluginInstallOutcome.Rejected(
                PluginInstallStage.PLUGIN_INSTALLATION,
                PluginInstallFailure.ProcessExited(23),
            ),
            installCodexPlugin(root, executor::execute),
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
            installCodexPlugin(root) { throw AssertionError("Codex must not run for unverified payloads") },
        )
    }
}

private val marketplaceList = listOf("codex", "plugin", "marketplace", "list", "--json")
private val pluginList = listOf("codex", "plugin", "list", "--marketplace", "kast", "--json")
private val pluginAdd = listOf("codex", "plugin", "add", "kast@kast", "--json")

private data class Step(
    val arguments: List<String>,
    val output: String = "",
    val observation: ProcessObservation = ProcessObservation.Exited(0, output),
)

private class PluginScript(vararg steps: Step) {
    private val remaining = ArrayDeque(steps.toList())

    fun execute(arguments: List<String>): ProcessObservation {
        val next = remaining.removeFirstOrNull() ?: throw AssertionError("unexpected Codex invocation: $arguments")
        assertEquals(next.arguments, arguments)
        return next.observation
    }

    fun assertConsumed() = assertEquals(emptyList<Step>(), remaining.toList())
}

private fun marketplaces(vararg roots: Path): String =
    Json.encodeToString(
        PluginTestMarketplaces(
            roots.map {
                PluginTestMarketplaceObservation(
                    "kast",
                    it.toString(),
                    PluginTestMarketplaceSource("local", it.toString()),
                )
            }
        )
    )

private fun plugins(vararg entries: PluginTestPluginObservation): String =
    Json.encodeToString(PluginTestPlugins(entries.toList()))

private fun plugin(marketplace: Path): PluginTestPluginObservation =
    PluginTestPluginObservation(
        pluginId = "kast@kast",
        name = "kast",
        marketplaceName = "kast",
        version = "1.2.3",
        installed = true,
        enabled = true,
        source = PluginTestSource("local", marketplace.resolve("plugins/kast").toString()),
        marketplaceSource = PluginTestMarketplaceSource("local", marketplace.toString()),
    )

private fun pluginFixture(temporary: Path): Path {
    val (root, _) = integrationFixture(temporary.toRealPath(), HarnessConnection.CODEX_MCP, "#!/bin/sh\nexit 0\n")
    val installation = root.resolve("installation").toRealPath()
    val launcher = installation.resolve("bin/kast-mcp-complete")
    launcher.toFile().setExecutable(true)
    val bundle = installation.resolve(AGENT_TOOLS_RELATIVE_PATH)
    val documents =
        mapOf(
            ".agents/plugins/marketplace.json" to
                Json.encodeToString(
                    PluginTestMarketplace(
                        "kast",
                        listOf(PluginTestMarketplaceEntry("kast", PluginTestSource("local", "./plugins/kast"))),
                    )
                ),
            "plugins/kast/.codex-plugin/plugin.json" to Json.encodeToString(PluginTestManifest("kast", "1.2.3")),
            "plugins/kast/.mcp.json" to
                Json.encodeToString(
                    PluginTestMcp(
                        PluginTestMcpServers(
                            PluginTestMcpServer("/bin/bash", listOf("-c", "exec configured-kast-mcp-launcher"))
                        )
                    )
                ),
            "plugins/kast/skills/kast/SKILL.md" to
                "---\nname: kast\ndescription: Query Kotlin with Kast.\n---\nUse the installed Kast tools.\n",
        )
    documents.forEach { (relative, content) ->
        val file = bundle.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
    val manifest = (readBundledManifest(installation) as BundledManifestRead.Read).manifest
    val files = Files.walk(bundle).use { it.filter(Files::isRegularFile).toList() }
    Files.writeString(
        installation.resolve("installation.json"),
        Json.encodeToString(
            manifest.copy(
                payloadFiles =
                    manifest.payloadFiles +
                        files.map {
                            BundledPayload(installation.relativize(it).toString(), "sha256:${sha256(it)}", 420)
                        }
            )
        ),
    )
    assertFalse(Files.exists(bundle.resolve("unmanifested")))
    return root
}
