package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

@Serializable internal data class PluginTestManifest(val name: String, val version: String)

@Serializable internal data class PluginTestMarketplace(val name: String, val plugins: List<PluginTestMarketplaceEntry>)

@Serializable internal data class PluginTestMarketplaceEntry(val name: String, val source: PluginTestSource)

@Serializable internal data class PluginTestSource(val source: String, val path: String)

@Serializable internal data class PluginTestMcp(val mcpServers: PluginTestMcpServers)

@Serializable internal data class PluginTestMcpServers(val kast: PluginTestMcpServer)

@Serializable
internal data class PluginTestMcpServer(
    val command: String,
    val args: List<String>,
    val type: String = "stdio",
    val env: Map<String, String>? = null,
    @SerialName("env_vars") val envVars: List<String> = emptyList(),
    val cwd: String? = null,
)

@Serializable internal data class PluginTestMarketplaces(val marketplaces: List<PluginTestMarketplaceObservation>)

@Serializable
internal data class PluginTestMarketplaceObservation(
    val name: String,
    val root: String,
    val marketplaceSource: PluginTestMarketplaceSource,
)

@Serializable internal data class PluginTestMarketplaceSource(val sourceType: String, val source: String)

@Serializable internal data class PluginTestPlugins(val installed: List<PluginTestPluginObservation>)

@Serializable
internal data class PluginTestPluginObservation(
    val pluginId: String,
    val name: String,
    val marketplaceName: String,
    val version: String,
    val installed: Boolean,
    val enabled: Boolean,
    val source: PluginTestSource,
    val marketplaceSource: PluginTestMarketplaceSource,
)

internal val marketplaceList = listOf("codex", "plugin", "marketplace", "list", "--json")
internal val pluginList = listOf("codex", "plugin", "list", "--marketplace", "kast", "--json")
internal val pluginAdd = listOf("codex", "plugin", "add", "kast@kast", "--json")

internal data class PluginStep(
    val arguments: List<String>,
    val output: String = "",
    val observation: ProcessObservation = ProcessObservation.Exited(0, output),
)

internal class PluginScript(vararg steps: PluginStep) {
    private val remaining = ArrayDeque(steps.toList())

    fun execute(arguments: List<String>): ProcessObservation {
        val next = remaining.removeFirstOrNull() ?: throw AssertionError("unexpected Codex invocation: $arguments")
        assertEquals(next.arguments, arguments)
        return next.observation
    }

    fun assertConsumed() = assertEquals(emptyList<PluginStep>(), remaining.toList())
}

internal fun mcpServers(vararg servers: CodexServer): String = managementJson.encodeToString(servers.toList())

internal fun bundledMcpServer(): CodexServer =
    CodexServer(
        "kast",
        CodexTransport(
            "stdio",
            "/bin/bash",
            listOf("-c", "exec \"\${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-mcp-complete\""),
        ),
    )

internal fun marketplaces(vararg roots: Path): String =
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

internal fun plugins(vararg entries: PluginTestPluginObservation): String =
    Json.encodeToString(PluginTestPlugins(entries.toList()))

internal fun plugin(marketplace: Path): PluginTestPluginObservation =
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

internal fun pluginFixture(temporary: Path): Path {
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
                            PluginTestMcpServer(bundledMcpServer().transport.command, bundledMcpServer().transport.args)
                        )
                    )
                ),
            "plugins/kast/references/onboarding.md" to "Use the installed Kast tools.\n",
        )
    (documents + sharedPluginDocuments()).forEach { (relative, content) ->
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

private fun sharedPluginDocuments(): Map<String, String> =
    listOf(
            ".github/plugin/plugin.json",
            "hooks/hooks.json",
            "hooks/copilot.json",
            "hooks/session_start.py",
            "hooks/session_start.py.lock",
            "references/query-schema.json",
            "references/query-examples.json",
        )
        .associate { relative ->
            "plugins/kast/$relative" to Files.readString(Path.of("../../agent-tools/$relative"))
        }
