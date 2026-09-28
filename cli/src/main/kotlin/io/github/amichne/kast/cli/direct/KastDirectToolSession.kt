package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.cli.ide.executeAdmittedPublicTool
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.cli.mcp.McpInvestigationTools
import io.github.amichne.kast.cli.mcp.McpSingleChangeTool
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.registry.SupportToolIdentity
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/** Shared native tool composition. The client protocol is chosen by the caller. */
internal class KastDirectToolSession(
    val catalog: List<DirectToolDocument>,
    val root: () -> CanonicalRootDiscovery,
    val start: (CanonicalRoot) -> Refinement<Unit, DaemonOperationFailure>,
    val invokePublic: (AdmittedPublicTool) -> CliExit,
    val invokeSupport: (SupportToolIdentity, JsonObject) -> CliExit = { _, _ ->
        error("No support tool binding")
    },
) {
    fun invoke(name: String, arguments: JsonObject): CliExit =
        SupportToolIdentity.entries.singleOrNull { it.toolName == name }?.let { invokeSupport(it, arguments) }
            ?: admitAndInvokePublic(name, arguments, invokePublic)

    fun invokeAdmitted(request: AdmittedPublicTool): CliExit = invokePublic(request)

    companion object {
        @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
        fun installed(directory: Path, home: Path, environment: Map<String, String>): KastDirectToolSession? {
            val catalog = installedHostedBootstrap().tools.associateBy { it.name }
            val selected =
                CanonicalAgentToolDefinitions.all.filter { definition ->
                    PublicToolIdentity.entries.any { it.toolName == definition.name.value }
                }
            if (selected.size != PublicToolIdentity.entries.size || selected.any { it.name.value !in catalog })
                return null
            val root = { FilesystemCanonicalRootDiscovery.discover(directory) }
            val boundRoot = (root() as? CanonicalRootDiscovery.Discovered)?.root?.path ?: directory
            val read = mcpWorkspaceOperationClient(home, environment)
            val capabilities =
                ExistingIdeCliCapabilities(
                    FilesystemCanonicalRootDiscovery,
                    configuredExistingIdeClient(home, environment),
                    read,
                )
            val change = McpSingleChangeTool.installed(root = boundRoot, home = home, capabilities = capabilities)
            val invokePublic: (AdmittedPublicTool) -> CliExit = { request ->
                if (request.identity == PublicToolIdentity.ADD_DECLARATION) change.invoke(request)
                else executeAdmittedPublicTool(request, directory, capabilities)
            }
            val investigation =
                McpInvestigationTools(
                    directory,
                    capabilities,
                    selected
                        .filter { it.name.value != PublicToolIdentity.ADD_DECLARATION.toolName }
                        .map { it.operation.operation.id.value.uppercase().replace('.', '_') }
                        .toSet(),
                )
            return KastDirectToolSession(
                catalog = selected.map { catalog.getValue(it.name.value).directToolDocument() } + directSupportTools(),
                root = root,
                start = read::start,
                invokePublic = invokePublic,
                invokeSupport = investigation::invoke,
            )
        }
    }
}

private fun admitAndInvokePublic(
    name: String,
    arguments: JsonObject,
    invoke: (AdmittedPublicTool) -> CliExit,
): CliExit {
    val identity =
        PublicToolIdentity.entries.singleOrNull { it.toolName == name }
            ?: return boundaryExit(CliBoundaryExitStatus.USAGE, "direct-tool-unsupported")
    return when (val admitted = PublicToolContract.admit(identity, arguments)) {
        is Refinement.Refined -> invoke(admitted.value)
        is Refinement.Rejected -> boundaryExit(CliBoundaryExitStatus.USAGE, "invalid-public-tool-arguments")
    }
}
