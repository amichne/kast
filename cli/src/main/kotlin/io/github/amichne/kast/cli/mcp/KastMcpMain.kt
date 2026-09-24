package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.InstalledInvocationBinding
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.cli.ide.executeExistingIdeCli
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.cli.installedServerBindings
import io.github.amichne.kast.cli.supportsLiveEvidence
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.io.BufferedInputStream
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/** The MCP client owns this stdio process. No app server or broker is started by this transport. */
object KastMcpMain {
    @JvmStatic
    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    fun main(args: Array<String>) {
        if (args.isNotEmpty()) return
        val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        if (graph !is CliCommandGraphConstruction.Created) return
        val catalog = installedHostedBootstrap().tools.associateBy { it.name }
        val selected = CanonicalAgentToolDefinitions.all.filter { it.operation.operation.supportsLiveEvidence() }
        val visible = mcpVisibleDefinitions(selected)
        val bindings = installedServerBindings(graph.factory.surface).associateBy { it.tool.name }
        if (selected.any { it.name.value !in catalog || it.name.value !in bindings }) return
        val directory = Path.of("").toAbsolutePath()
        val boundRoot =
            (FilesystemCanonicalRootDiscovery.discover(directory) as? CanonicalRootDiscovery.Discovered)?.root?.path
                ?: directory
        val home = Path.of(System.getProperty("user.home"))
        val read = mcpWorkspaceOperationClient(home, System.getenv())
        val capabilities =
            ExistingIdeCliCapabilities(
                FilesystemCanonicalRootDiscovery,
                configuredExistingIdeClient(home, System.getenv()),
                read,
            )
        val invokeCanonical: (String, JsonObject) -> CliExit = invokeCanonical@{ name, arguments ->
            val definition = selected.single { it.name.value == name }
            val command =
                when (val binding = definition.inputBinding) {
                    is AgentToolInputBinding.Facade -> listOf("tool", binding.identity.toolName)
                    AgentToolInputBinding.Canonical ->
                        when (val route = bindings.getValue(name).invocation) {
                            is InstalledInvocationBinding.Cli -> route.document.invocation.command
                            InstalledInvocationBinding.HostedOnly ->
                                return@invokeCanonical boundaryExit(
                                    CliBoundaryExitStatus.USAGE,
                                    "mcp-tool-unsupported",
                                )
                        }
                }
            executeExistingIdeCli(
                argv = command,
                start = directory,
                capabilities = capabilities,
                requestInput = CliRequestDocumentInput.Provided(arguments.toString()),
            )
        }
        val investigation =
            McpInvestigationTools(
                directory,
                capabilities,
                selected.map { it.operation.operation.id.value.uppercase().replace('.', '_') }.toSet(),
                invokeCanonical,
            )
        val change =
            McpSingleChangeTool.installed(
                root = boundRoot,
                home = home,
                inputSchema = catalog.getValue("change").inputSchema,
                capabilities = capabilities,
            )
        KastMcpServer(
                catalog = visible.map { catalog.getValue(it.name.value) },
                invoke = invokeCanonical,
                root = { FilesystemCanonicalRootDiscovery.discover(directory) },
                supplemental = investigation.tools + change.tool,
                onInitialize = read::start,
            )
            .run(BufferedInputStream(System.`in`), System.out)
    }
}
