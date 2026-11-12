package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.McpWorkspaceOperationClient
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.workspaceAdmission
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.directToolDocument
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.cli.ide.executeAdmittedPublicTool
import io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownFailure
import io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownOutcome
import io.github.amichne.kast.cli.installation.shutdownManagedHeadless
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.cli.mcp.McpSingleChangeTool
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.NativeComponentAccess
import io.github.amichne.kast.distribution.cli.NativeComponentDeployment
import io.github.amichne.kast.distribution.cli.NativeComponentIdentity
import io.github.amichne.kast.distribution.cli.NativeComponentInput
import io.github.amichne.kast.distribution.cli.NativeComponentLease
import io.github.amichne.kast.distribution.cli.NativeComponentStop
import io.github.amichne.kast.distribution.cli.NativeComponentWorkspace
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate test-native entry. No installed launcher, product record, or public admission override is emitted. */
object NativeComponentQualificationMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.firstOrNull() == "--internal-entrypoint=jq") {
            PublicJqWorker.main(arguments.drop(1).toTypedArray())
            return
        }
        val command = parseNativeComponentCommand(arguments.toList())
        if (command is NativeComponentCommand.Execute) {
            exitProcess(
                runPublicCliInvocation(
                        { prepareNativeComponentQuery(command, System.`in`) },
                        FileOutputStream(FileDescriptor.out),
                    )
                    .code
            )
        }
        val reply =
            when (command) {
                NativeComponentCommand.Invalid -> NativeComponentReply.Rejected(NativeComponentFailure.Arguments)
                is NativeComponentCommand.Admit -> inspectComponent(NativeComponentWorkspace.admit(command.input))
                is NativeComponentCommand.Inspect -> inspectComponent(NativeComponentWorkspace.select(command.input))
                is NativeComponentCommand.Stop -> stopComponent(command)
                is NativeComponentCommand.Execute -> error("Execute has its own output owner")
            }
        println(componentJson.encodeToString<NativeComponentReply>(reply))
        exitProcess(if (reply is NativeComponentReply.Rejected) 1 else 0)
    }
}

internal sealed interface NativeComponentCommand {
    data object Invalid : NativeComponentCommand

    data class Admit(val input: NativeComponentInput) : NativeComponentCommand

    data class Inspect(val input: NativeComponentInput) : NativeComponentCommand

    data class Stop(val input: NativeComponentInput, val scope: NativeComponentStop) : NativeComponentCommand

    data class Execute(val input: NativeComponentInput, val arguments: List<String>) : NativeComponentCommand
}

internal fun parseNativeComponentCommand(arguments: List<String>): NativeComponentCommand {
    val required =
        setOf(
            "--store",
            "--workspace",
            "--runtime-archive",
            "--runtime-sha256",
            "--control",
            "--control-sha256",
            "--version",
            "--source-revision",
        )
    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < arguments.size && arguments[index].startsWith("--")) {
        val option = arguments[index]
        if (option !in required || option in values || index + 1 >= arguments.size)
            return NativeComponentCommand.Invalid
        values[option] = arguments[index + 1]
        index += 2
    }
    if (values.keys != required) return NativeComponentCommand.Invalid
    val input =
        NativeComponentInput(
            store = values.getValue("--store"),
            workspace = values.getValue("--workspace"),
            runtimeArchive = values.getValue("--runtime-archive"),
            runtimeSha256 = values.getValue("--runtime-sha256"),
            control = values.getValue("--control"),
            controlSha256 = values.getValue("--control-sha256"),
            version = values.getValue("--version"),
            sourceRevision = values.getValue("--source-revision"),
        )
    val command = arguments.drop(index)
    return when {
        command == listOf("admit") -> NativeComponentCommand.Admit(input)
        command == listOf("inspect") -> NativeComponentCommand.Inspect(input)
        command == listOf("stop") -> NativeComponentCommand.Stop(input, NativeComponentStop.SELECTED)
        command == listOf("stop", "--all") -> NativeComponentCommand.Stop(input, NativeComponentStop.ALL)
        command.size >= 3 && command.take(2) == listOf("exec", "--") ->
            NativeComponentCommand.Execute(input, command.drop(2))
        else -> NativeComponentCommand.Invalid
    }
}

/** Public parsing, schema admission, execution, rendering and exit policy remain production-owned. */
internal fun prepareNativeComponentQuery(
    command: NativeComponentCommand.Execute,
    input: InputStream,
): PublicPreparedOutput =
    preparePublicCli(
        argv = command.arguments,
        input = input,
        directory = Path.of(command.input.workspace),
        session = { directory, catalog -> componentSession(command.input, directory, catalog) },
        invoke = ::invokePublicSession,
        diagnostic = System.err::println,
    )

private fun componentSession(
    input: NativeComponentInput,
    directory: Path,
    catalog: PublicToolCatalog,
): KastDirectToolSession =
    KastDirectToolSession(
        catalog =
            installedHostedBootstrap()
                .tools
                .filter { tool -> PublicToolIdentity.entries.any { it.toolName == tool.name } }
                .map { tool ->
                    val identity = PublicToolIdentity.entries.single { it.toolName == tool.name }
                    tool.directToolDocument().copy(inputSchema = PublicToolContract.parameters(identity, catalog))
                },
        root = { FilesystemCanonicalRootDiscovery.discover(directory) },
        invokePublic = { request -> invokeComponent(input, request) },
        queryCatalog = catalog,
    )

private fun invokeComponent(input: NativeComponentInput, request: AdmittedPublicTool): CliExit {
    val selected =
        when (val current = NativeComponentWorkspace.select(input)) {
            is Refinement.Rejected -> return componentExit(NativeComponentFailure.Workspace(current.failure))
            is Refinement.Refined -> current.value
        }
    val access =
        when (request.canonical.workspaceAdmission()) {
            WorkspaceOperationAdmission.PREPARE -> NativeComponentAccess.PREPARE
            WorkspaceOperationAdmission.EXISTING_AUTHORITY -> NativeComponentAccess.EXISTING_AUTHORITY
        }
    val lease =
        when (val opened = selected.open(access)) {
            is Refinement.Rejected -> return componentExit(NativeComponentFailure.Workspace(opened.failure))
            is Refinement.Refined -> opened.value
        }
    return lease.use { acquired ->
        when (val prepared = componentClient(selected, acquired, access)) {
            is Refinement.Rejected -> componentExit(prepared.failure)
            is Refinement.Refined ->
                prepared.value.use { client ->
                    client.execute(request, selected, acquired.deployment)
                }
        }
    }
}

private class ComponentClient(val owner: McpWorkspaceOperationClient, val read: DaemonOperationClient) : AutoCloseable {
    fun execute(
        request: AdmittedPublicTool,
        workspace: NativeComponentWorkspace,
        deployment: NativeComponentDeployment,
    ): CliExit {
        val capabilities =
            ExistingIdeCliCapabilities(
                FilesystemCanonicalRootDiscovery,
                configuredExistingIdeClient(deployment.home, deployment.environment),
                read,
            )
        return if (request.identity in setOf(PublicToolIdentity.ADD_DECLARATION, PublicToolIdentity.REPLACE_BODY))
            McpSingleChangeTool.installed(workspace.root.path, capabilities).invoke(request)
        else executeAdmittedPublicTool(request, workspace.root.path, capabilities)
    }

    override fun close() = owner.close()
}

private fun componentClient(
    workspace: NativeComponentWorkspace,
    lease: NativeComponentLease,
    access: NativeComponentAccess,
): Refinement<ComponentClient, NativeComponentFailure> =
    when (access) {
        NativeComponentAccess.EXISTING_AUTHORITY -> {
            val owner = mcpWorkspaceOperationClient(lease.deployment.home, lease.deployment.environment)
            Refinement.Refined(ComponentClient(owner, owner))
        }
        NativeComponentAccess.PREPARE ->
            when (
                val prepared = lease.prepare { remaining ->
                    prepareComponentClient(workspace, lease.deployment, remaining)
                }
            ) {
                is Refinement.Rejected -> Refinement.Rejected(NativeComponentFailure.Workspace(prepared.failure))
                is Refinement.Refined -> prepared.value
            }
    }

private fun prepareComponentClient(
    workspace: NativeComponentWorkspace,
    deployment: NativeComponentDeployment,
    remaining: ElapsedTimeAllowance,
): Refinement<ComponentClient, NativeComponentFailure> {
    val setting =
        when (remaining) {
            is ElapsedTimeAllowance.Bounded -> remaining.limit.value.toString()
            ElapsedTimeAllowance.Unlimited -> "indefinite"
        }
    val owner =
        mcpWorkspaceOperationClient(
            deployment.home,
            deployment.environment + ("KAST_WORKSPACE_PREPARATION_WAIT" to setting),
        )
    var transferred = false
    try {
        return when (val prepared = runBlocking { owner.prepared(workspace.root) }) {
            is Refinement.Rejected -> Refinement.Rejected(NativeComponentFailure.Preparation(prepared.failure))
            is Refinement.Refined -> {
                transferred = true
                Refinement.Refined(ComponentClient(owner, prepared.value))
            }
        }
    } finally {
        if (!transferred) owner.close()
    }
}

private fun inspectComponent(selected: Refinement<NativeComponentWorkspace, ContainerFailure>): NativeComponentReply =
    when (selected) {
        is Refinement.Rejected -> NativeComponentReply.Rejected(NativeComponentFailure.Workspace(selected.failure))
        is Refinement.Refined ->
            when (val opened = selected.value.open(NativeComponentAccess.EXISTING_AUTHORITY)) {
                is Refinement.Rejected ->
                    NativeComponentReply.Rejected(NativeComponentFailure.Workspace(opened.failure))
                is Refinement.Refined ->
                    opened.value.use { lease ->
                        val deployment = lease.deployment
                        NativeComponentReply.Inspected(
                            identity = selected.value.identity,
                            workspace = deployment.workspace.toString(),
                            workspaceId = deployment.workspaceId,
                            workspaceState = deployment.state.toString(),
                            runtimeHome = deployment.runtimeHome.toString(),
                            home = deployment.home.toString(),
                            javaHome = deployment.javaHome.toString(),
                            profile = deployment.profile.toString(),
                        )
                    }
            }
    }

private fun stopComponent(command: NativeComponentCommand.Stop): NativeComponentReply {
    val workspace =
        when (val selected = NativeComponentWorkspace.select(command.input)) {
            is Refinement.Rejected ->
                return NativeComponentReply.Rejected(NativeComponentFailure.Workspace(selected.failure))
            is Refinement.Refined -> selected.value
        }
    var shutdown: ManagedHeadlessShutdownOutcome = ManagedHeadlessShutdownOutcome.Completed
    val stopped =
        workspace.stop(command.scope) { deployment ->
            shutdown = shutdownManagedHeadless(deployment.profile, deployment.home, deployment.state)
            when (shutdown) {
                ManagedHeadlessShutdownOutcome.Completed -> Refinement.Refined(Unit)
                is ManagedHeadlessShutdownOutcome.Rejected -> Refinement.Rejected(ContainerFailure.SHUTDOWN_FAILED)
            }
        }
    return when (val result = shutdown) {
        is ManagedHeadlessShutdownOutcome.Rejected ->
            NativeComponentReply.Rejected(NativeComponentFailure.Shutdown(result.failure))
        ManagedHeadlessShutdownOutcome.Completed ->
            when (stopped) {
                is Refinement.Rejected ->
                    NativeComponentReply.Rejected(NativeComponentFailure.Workspace(stopped.failure))
                is Refinement.Refined -> NativeComponentReply.Stopped(workspace.identity, stopped.value)
            }
    }
}

@Serializable
internal sealed interface NativeComponentFailure {
    @Serializable @SerialName("ARGUMENTS") data object Arguments : NativeComponentFailure

    @Serializable @SerialName("WORKSPACE") data class Workspace(val failure: ContainerFailure) : NativeComponentFailure

    @Serializable
    @SerialName("PREPARATION")
    data class Preparation(val failure: DaemonOperationFailure) : NativeComponentFailure

    @Serializable
    @SerialName("SHUTDOWN")
    data class Shutdown(val failure: ManagedHeadlessShutdownFailure) : NativeComponentFailure
}

@Serializable
internal sealed interface NativeComponentReply {
    @Serializable
    @SerialName("INSPECTED")
    data class Inspected(
        val identity: NativeComponentIdentity,
        val workspace: String,
        val workspaceId: String,
        val workspaceState: String,
        val runtimeHome: String,
        val home: String,
        val javaHome: String,
        val profile: String,
    ) : NativeComponentReply

    @Serializable
    @SerialName("STOPPED")
    data class Stopped(val identity: NativeComponentIdentity, val workspaces: List<String>) : NativeComponentReply

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val failure: NativeComponentFailure) : NativeComponentReply
}

private fun componentExit(failure: NativeComponentFailure) =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.RUNTIME,
        componentRejection.create(NativeComponentReply.Rejected(failure)),
    )

private val componentJson = Json { encodeDefaults = true }
private val componentRejection = CanonicalJsonDocument.generated(NativeComponentReply.Rejected.serializer())
