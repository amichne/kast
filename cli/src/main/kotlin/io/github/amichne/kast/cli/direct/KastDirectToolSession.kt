package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.executeAdmittedPublicTool
import io.github.amichne.kast.cli.mcp.McpInvestigationTools
import io.github.amichne.kast.cli.mcp.McpSingleChangeTool
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.mcp.admitMcpHealthInput
import io.github.amichne.kast.cli.rpc.InvocationRecordKind
import io.github.amichne.kast.cli.rpc.OneShotInvocationRecord
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.AgentToolDefinition
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.registry.SupportToolIdentity
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/** Shared native tool composition. The client protocol is chosen by the caller. */
internal class KastDirectToolSession(
    private val registration: DirectToolRegistration,
    val root: () -> CanonicalRootDiscovery,
    val invokePublic: (AdmittedPublicTool) -> CliExit,
    val invokeSupport: (SupportToolIdentity, JsonObject) -> CliExit = { _, _ ->
        error("No support tool binding")
    },
    val admission: () -> InstalledToolAdmission = { InstalledToolAdmission.AVAILABLE },
    val queryCatalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    val beginInvocation: (InvocationRecordKind) -> OneShotInvocationRecord? = { null },
) {
    /** Invocation admission uses compiled registrations; schema projection is only a discovery effect. */
    val catalog: List<DirectToolDocument>
        get() = registration.catalog

    fun supports(name: String): Boolean = registration.supports(name)

    constructor(
        catalog: List<DirectToolDocument>,
        root: () -> CanonicalRootDiscovery,
        invokePublic: (AdmittedPublicTool) -> CliExit,
        invokeSupport: (SupportToolIdentity, JsonObject) -> CliExit = { _, _ -> error("No support tool binding") },
        admission: () -> InstalledToolAdmission = { InstalledToolAdmission.AVAILABLE },
        queryCatalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ) : this(
        DirectToolRegistration.fromDocuments(catalog),
        root,
        invokePublic,
        invokeSupport,
        admission,
        queryCatalog,
    )

    fun invoke(name: String, arguments: JsonObject): CliExit =
        if (admission() != InstalledToolAdmission.AVAILABLE)
            boundaryExit(CliBoundaryExitStatus.RUNTIME, "installation-shutdown")
        else
            SupportToolIdentity.entries.singleOrNull { it.toolName == name }?.let { invokeSupport(it, arguments) }
                ?: admitAndInvokePublic(name, arguments, queryCatalog, invokePublic)

    fun invokeAdmitted(request: AdmittedPublicTool): CliExit =
        if (request.catalog != queryCatalog)
            CliExit.PublicInputRejected(io.github.amichne.kast.appserver.query.PublicToolInputFailure.SchemaMismatch)
        else if (admission() == InstalledToolAdmission.AVAILABLE) invokePublic(request)
        else boundaryExit(CliBoundaryExitStatus.RUNTIME, "installation-shutdown")

    companion object {
        fun installed(
            directory: Path,
            environment: Map<String, String>,
            queryCatalog: PublicToolCatalog = PublicToolCatalog.COMMON,
        ): KastDirectToolSession? =
            when (val admitted = InstalledClientBinding.current(environment)) {
                is Refinement.Rejected -> null
                is Refinement.Refined -> installed(directory, admitted.value, queryCatalog)
            }

        fun installed(
            directory: Path,
            binding: InstalledClientBinding,
            queryCatalog: PublicToolCatalog,
        ): KastDirectToolSession? {
            val selected =
                CanonicalAgentToolDefinitions.all.filter { definition ->
                    PublicToolIdentity.entries.any { it.toolName == definition.name.value }
                }
            if (selected.size != PublicToolIdentity.entries.size) return null
            val root = { FilesystemCanonicalRootDiscovery.discover(directory) }
            val read = CoordinatedToolClient(binding)
            val capabilities =
                ExistingIdeCliCapabilities(
                    FilesystemCanonicalRootDiscovery,
                    coordinatedStatus(read),
                    read,
                )
            val changes = McpSingleChangeTool.installed(root = directory, capabilities = capabilities)
            val invokePublic: (AdmittedPublicTool) -> CliExit = { request ->
                if (request.identity in setOf(PublicToolIdentity.ADD_DECLARATION, PublicToolIdentity.REPLACE_BODY))
                    changes.invoke(request)
                else executeAdmittedPublicTool(request, directory, capabilities)
            }
            val expectedOperations =
                selected
                    .filter {
                        it.name.value !in
                            setOf(
                                PublicToolIdentity.ADD_DECLARATION.toolName,
                                PublicToolIdentity.REPLACE_BODY.toolName,
                            )
                    }
                    .map { it.operation.operation.id.value.uppercase().replace('.', '_') }
                    .toSet()
            return KastDirectToolSession(
                registration = installedToolRegistration(selected, queryCatalog),
                root = root,
                admission = binding::observe,
                invokePublic = invokePublic,
                invokeSupport = { identity, arguments ->
                    invokeCoordinatedSupport(directory, capabilities, identity, arguments, expectedOperations)
                },
                queryCatalog = queryCatalog,
                beginInvocation = { kind -> OneShotInvocationRecord.begin(binding, kind) },
            )
        }
    }
}

private fun installedToolRegistration(selected: List<AgentToolDefinition>, queryCatalog: PublicToolCatalog) =
    DirectToolRegistration(
        selected.map { it.name }.toSet() + directToolName(SupportToolIdentity.HEALTH_CHECK.toolName)
    ) {
        observeDirectToolStage(DirectToolStage.DIRECT_CATALOG_PROJECTION) {
            selected.map { definition ->
                val identity = PublicToolIdentity.entries.single { it.toolName == definition.name.value }
                DirectToolDocument(
                    name = definition.name.value,
                    description = identity.descriptionFor(queryCatalog),
                    inputSchema = PublicToolContract.parameters(identity, queryCatalog),
                    outputSchema = McpStructuredResults.schemaFor(definition.name.value),
                    effect = definition.operation.effect,
                )
            } + directSupportTools()
        }
    }

private fun invokeCoordinatedSupport(
    directory: Path,
    capabilities: ExistingIdeCliCapabilities,
    identity: SupportToolIdentity,
    arguments: JsonObject,
    expectedOperations: Set<String>,
): CliExit =
    when (identity) {
        SupportToolIdentity.WORKSPACE_LIFECYCLE -> boundaryExit(CliBoundaryExitStatus.USAGE, "direct-tool-unsupported")
        SupportToolIdentity.HEALTH_CHECK ->
            when (val admitted = admitMcpHealthInput(arguments)) {
                is Refinement.Rejected -> admitted.failure
                is Refinement.Refined ->
                    McpInvestigationTools(directory, expectedOperations, capabilities).health(admitted.value)
            }
    }

private fun coordinatedStatus(read: DaemonOperationClient): ExistingIdeClient = ExistingIdeClient { root, operation ->
    if (operation != ExistingIdeOperation.Status) {
        ExistingIdeExchange.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
    } else
        when (val status = read.read(root, DaemonOperationCall.WorkspaceStatus)) {
            is DaemonOperationResult.Complete -> ExistingIdeExchange.Received(status.document)
            is DaemonOperationResult.Hosted -> ExistingIdeExchange.Received(status.document)
            is DaemonOperationResult.OperationRejected -> ExistingIdeExchange.HostRejected(status.document)
            is DaemonOperationResult.Qualified -> ExistingIdeExchange.HostRejected(status.document)
            is DaemonOperationResult.Rejected -> ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        }
}

private fun admitAndInvokePublic(
    name: String,
    arguments: JsonObject,
    catalog: PublicToolCatalog,
    invoke: (AdmittedPublicTool) -> CliExit,
): CliExit {
    val identity =
        PublicToolIdentity.entries.singleOrNull { it.toolName == name }
            ?: return boundaryExit(CliBoundaryExitStatus.USAGE, "direct-tool-unsupported")
    return when (val admitted = PublicToolContract.admit(identity, arguments, catalog)) {
        is Refinement.Refined -> invoke(admitted.value)
        is Refinement.Rejected -> CliExit.PublicInputRejected(admitted.failure)
    }
}
