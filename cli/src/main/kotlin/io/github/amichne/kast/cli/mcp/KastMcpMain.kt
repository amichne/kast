package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.executeInstalledKastCli
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.FilesystemBrokerTrustRegistrar
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.cli.ide.executeExistingIdeCli
import io.github.amichne.kast.cli.installedServerProjection
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.io.BufferedInputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Codex owns this stdio process. No Codex App Server or broker is started by the MCP transport. */
object KastMcpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "approve") {
            kotlin.system.exitProcess(McpApprovalHelper.run(args.drop(1)))
        }
        if (args.isNotEmpty()) return
        val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        if (graph !is CliCommandGraphConstruction.Created) return
        val projection = installedServerProjection(graph.factory.surface)
        val catalog = projection.hostedBootstrap.tools.associateBy { it.name }
        val invocation = projection.cliInvocations.operations.associateBy { it.toolName }
        // Workspace lifecycle belongs to the installed service. Mutation consumes an exact-plan grant.
        val selected = CanonicalAgentToolDefinitions.defaultAppServerTools
            .filterNot { it.name.value == "workspace_lifecycle" }
            .map { it.name.value }
        if (selected.any { it !in catalog || it !in invocation }) return
        val directory = Path.of("").toAbsolutePath()
        val home = Path.of(System.getProperty("user.home"))
        val approvals = McpApprovalStore(home)
        KastMcpServer(
            catalog = selected.map { catalog.getValue(it) },
            invoke = { name, arguments ->
                val command = invocation.getValue(name).invocation.command
                val mutation = name == "change_apply" || name == "change_recover"
                val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(directory) as? CanonicalRootDiscovery.Discovered)?.root?.path
                val grant = if (mutation && admittedRoot != null) approvals.take(name, arguments, admittedRoot) else null
                if (mutation && grant == null) boundaryExit(CliBoundaryExitStatus.USAGE, "approval-required-run-kast-mcp-approve")
                else executeExistingIdeCli(
                    argv = if (mutation) command + "--hosted-approved-invocation" else command,
                    start = directory,
                    capabilities =
                        ExistingIdeCliCapabilities(
                            FilesystemCanonicalRootDiscovery,
                            configuredExistingIdeClient(home, System.getenv()),
                            FilesystemBrokerTrustRegistrar(home),
                        ),
                    requestInput = CliRequestDocumentInput.Provided(
                        if (grant != null) mcpWire.encodeToString(McpApprovedArguments(arguments, grant))
                        else arguments.toString()
                    ),
                )
            },
            root = { FilesystemCanonicalRootDiscovery.discover(directory) },
            openWorkspace = {
                when (val found = FilesystemCanonicalRootDiscovery.discover(directory)) {
                    is CanonicalRootDiscovery.Rejected -> boundaryExit(CliBoundaryExitStatus.USAGE, "not-gradle-workspace")
                    is CanonicalRootDiscovery.Discovered -> executeInstalledKastCli(
                        listOf("workspace", "lifecycle"),
                        directory,
                        CliRequestDocumentInput.Provided(
                            mcpWire.encodeToString<WorkspaceLifecycleRequest>(
                                WorkspaceLifecycleRequest.Open(found.root.path.toString(), UUID.randomUUID().toString()),
                            ),
                        ),
                    )
                }
            },
        ).run(BufferedInputStream(System.`in`), System.out)
    }
}

internal class KastMcpServer(
    private val catalog: List<io.github.amichne.kast.cli.InstalledHostedToolDocument>,
    private val invoke: (String, JsonObject) -> CliExit,
    private val root: () -> CanonicalRootDiscovery,
    private val openWorkspace: (() -> CliExit)? = null,
    private val diagnostic: PrintStream = System.err,
) {
    private val tools = catalog.associateBy { it.name }
    private val workspaceOpenTool = McpTool(
        "workspace_open",
        "Open this session's exact Gradle repository in the selected IntelliJ IDEA host. Call when a Kast query reports that the IDE host or project is unavailable; then retry after the project is ready.",
        mcpWire.encodeToJsonElement(McpObjectSchema()),
    )

    fun run(input: BufferedInputStream, output: PrintStream) {
        while (true) {
            val line = readLine(input) ?: return
            val request = try {
                mcpWire.decodeFromString<McpRequest>(line)
            } catch (_: SerializationException) {
                continue
            }
            if (request.jsonrpc != "2.0") continue
            val id = request.id ?: continue
            val response = when (request.method) {
                "initialize" -> success(id, McpInitializeResult())
                "ping" -> success(id, McpEmptyResult())
                "tools/list" -> success(
                    id,
                    McpToolList(catalog.map { McpTool(it.name, it.description, it.inputSchema) } +
                        listOfNotNull(openWorkspace?.let { workspaceOpenTool })),
                )
                "tools/call" -> call(id, request.params)
                else -> McpResponse(id, error = McpError(-32601, "method-not-found"))
            }
            output.println(mcpWire.encodeToString(response))
            output.flush()
        }
    }

    private fun call(id: JsonElement, params: JsonElement?): McpResponse {
        val call = try {
            mcpWire.decodeFromJsonElement<McpToolCall>(params ?: return rejected(id, McpCallFailure.INVALID_ARGUMENTS))
        } catch (_: SerializationException) {
            return rejected(id, McpCallFailure.INVALID_ARGUMENTS)
        }
        if (call.name !in tools && !(call.name == workspaceOpenTool.name && openWorkspace != null))
            return rejected(id, McpCallFailure.UNKNOWN_TOOL)
        if (call.name == workspaceOpenTool.name && call.arguments.isNotEmpty())
            return rejected(id, McpCallFailure.INVALID_ARGUMENTS)
        report(call.name, McpCallStage.ADMISSION, McpCallOutcome.STARTED)
        if (root() !is CanonicalRootDiscovery.Discovered) {
            report(call.name, McpCallStage.ADMISSION, McpCallOutcome.REJECTED)
            return rejected(id, McpCallFailure.NOT_GRADLE_WORKSPACE)
        }
        // The existing CLI owns the schema, IDE identity, and semantic outcome.
        report(call.name, McpCallStage.EXECUTION, McpCallOutcome.STARTED)
        val exit = try {
            if (call.name == workspaceOpenTool.name) openWorkspace!!.invoke() else invoke(call.name, call.arguments)
        } catch (_: RuntimeException) {
            report(call.name, McpCallStage.EXECUTION, McpCallOutcome.REJECTED)
            return rejected(id, McpCallFailure.INVOCATION_FAILED)
        }
        report(
            call.name,
            McpCallStage.EXECUTION,
            if (exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected) McpCallOutcome.REJECTED
            else McpCallOutcome.COMPLETED,
        )
        return success(
            id,
            McpCallResult(
                content = listOf(McpTextContent(exit.document.value)),
                isError = exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected,
            ),
        )
    }

    private fun rejected(id: JsonElement, failure: McpCallFailure) =
        success(
            id,
            McpCallResult(listOf(McpTextContent(mcpWire.encodeToString(McpRejected(failure = failure)))), isError = true),
        )

    private fun report(tool: String, stage: McpCallStage, outcome: McpCallOutcome) {
        diagnostic.println(mcpWire.encodeToString(McpCallEvent(tool = tool, stage = stage, outcome = outcome)))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8)
            if (bytes.size() >= MAX_FRAME_BYTES) return null
            bytes.write(next)
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 1_048_576
    }
}

private val mcpWire = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private inline fun <reified T> success(id: JsonElement, result: T): McpResponse =
    McpResponse(id, mcpWire.encodeToJsonElement(result))

@Serializable
private data class McpRequest(val jsonrpc: String = "2.0", val id: JsonElement? = null, val method: String, val params: JsonElement? = null)

@Serializable
private data class McpResponse(val id: JsonElement, val result: JsonElement? = null, val error: McpError? = null, val jsonrpc: String = "2.0")

@Serializable
private data class McpInitializeResult(
    val protocolVersion: String = "2025-06-18",
    val capabilities: McpCapabilities = McpCapabilities(),
    val serverInfo: McpServerInfo = McpServerInfo(),
)

@Serializable
private data class McpCapabilities(val tools: McpEmptyResult = McpEmptyResult())

@Serializable
private data class McpServerInfo(val name: String = "kast", val version: String = "1")

@Serializable
private class McpEmptyResult

@Serializable
private data class McpToolList(val tools: List<McpTool>)

@Serializable
private data class McpTool(val name: String, val description: String, val inputSchema: JsonElement)

@Serializable
private data class McpObjectSchema(val type: String = "object", val properties: McpEmptyResult = McpEmptyResult(), val additionalProperties: Boolean = false)

@Serializable
private data class McpToolCall(val name: String, val arguments: JsonObject)

@Serializable
private data class McpCallResult(val content: List<McpTextContent>, val isError: Boolean)

@Serializable
private data class McpTextContent(val text: String, val type: String = "text")

@Serializable
private data class McpError(val code: Int, val message: String)

@Serializable
private data class McpRejected(val status: String = "rejected", val failure: McpCallFailure)

@Serializable
private enum class McpCallFailure {
    INVALID_ARGUMENTS,
    UNKNOWN_TOOL,
    NOT_GRADLE_WORKSPACE,
    INVOCATION_FAILED,
}

@Serializable
private data class McpCallEvent(
    val component: String = "kast-mcp",
    val tool: String,
    val stage: McpCallStage,
    val outcome: McpCallOutcome,
)

@Serializable
private enum class McpCallStage { ADMISSION, EXECUTION }

@Serializable
private enum class McpCallOutcome { STARTED, COMPLETED, REJECTED }
