package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.InstalledHostedToolDocument
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
import io.github.amichne.kast.cli.mcp.McpInvestigationTools
import io.github.amichne.kast.cli.mcp.McpSingleChangeTool
import io.github.amichne.kast.cli.mcp.McpSupplementalTool
import io.github.amichne.kast.cli.mcp.mcpVisibleDefinitions
import io.github.amichne.kast.cli.supportsLiveEvidence
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/** Shared native tool composition. The client protocol is chosen by the caller. */
internal class KastDirectToolSession(
    val catalog: List<InstalledHostedToolDocument>,
    val supplemental: List<McpSupplementalTool>,
    val root: () -> CanonicalRootDiscovery,
    val start: (CanonicalRoot) -> Refinement<Unit, DaemonOperationFailure>,
    val invokeCanonical: (String, JsonObject) -> CliExit,
) {
    fun invoke(name: String, arguments: JsonObject): CliExit =
        supplemental.singleOrNull { it.name == name }?.invoke?.invoke(arguments) ?: invokeCanonical(name, arguments)

    companion object {
        @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
        fun installed(directory: Path, home: Path, environment: Map<String, String>): KastDirectToolSession? {
            val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
            if (graph !is CliCommandGraphConstruction.Created) return null
            val catalog = installedHostedBootstrap().tools.associateBy { it.name }
            val selected = CanonicalAgentToolDefinitions.all.filter { it.operation.operation.supportsLiveEvidence() }
            val visible = mcpVisibleDefinitions(selected)
            val bindings = installedServerBindings(graph.factory.surface).associateBy { it.tool.name }
            if (selected.any { it.name.value !in catalog || it.name.value !in bindings }) return null
            val root = { FilesystemCanonicalRootDiscovery.discover(directory) }
            val boundRoot = (root() as? CanonicalRootDiscovery.Discovered)?.root?.path ?: directory
            val read = mcpWorkspaceOperationClient(home, environment)
            val capabilities =
                ExistingIdeCliCapabilities(
                    FilesystemCanonicalRootDiscovery,
                    configuredExistingIdeClient(home, environment),
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
                                        "direct-tool-unsupported",
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
            return KastDirectToolSession(
                catalog = visible.map { catalog.getValue(it.name.value) },
                supplemental = investigation.tools + change.tool,
                root = root,
                start = read::start,
                invokeCanonical = invokeCanonical,
            )
        }
    }
}
