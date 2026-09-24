package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
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
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.io.BufferedInputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** The MCP client owns this stdio process. No app server or broker is started by this transport. */
object KastMcpMain {
    @JvmStatic
    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "approve") {
            kotlin.system.exitProcess(McpApprovalHelper.run(args.drop(1)))
        }
        if (args.isNotEmpty()) return
        val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        if (graph !is CliCommandGraphConstruction.Created) return
        val catalog = installedHostedBootstrap().tools.associateBy { it.name }
        val selected = CanonicalAgentToolDefinitions.all.filter { it.operation.operation.supportsLiveEvidence() }
        val bindings = installedServerBindings(graph.factory.surface).associateBy { it.tool.name }
        if (selected.any { it.name.value !in catalog || it.name.value !in bindings }) return
        val directory = Path.of("").toAbsolutePath()
        val home = Path.of(System.getProperty("user.home"))
        val approvals = McpApprovalStore(home)
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
            val mutation = name == "change_apply" || name == "change_recover"
            val admittedRoot =
                (FilesystemCanonicalRootDiscovery.discover(directory) as? CanonicalRootDiscovery.Discovered)?.root?.path
            val grant = if (mutation && admittedRoot != null) approvals.take(name, arguments, admittedRoot) else null
            if (mutation && grant == null)
                boundaryExit(CliBoundaryExitStatus.USAGE, "approval-required-run-kast-mcp-approve")
            else
                executeExistingIdeCli(
                    argv = if (mutation) command + "--hosted-approved-invocation" else command,
                    start = directory,
                    capabilities = capabilities,
                    requestInput =
                        CliRequestDocumentInput.Provided(
                            if (grant != null) mcpWire.encodeToString(McpApprovedArguments(arguments, grant))
                            else arguments.toString()
                        ),
                )
        }
        val investigation =
            McpInvestigationTools(
                directory,
                capabilities,
                selected.map { it.operation.operation.id.value.uppercase().replace('.', '_') }.toSet(),
                invokeCanonical,
            )
        KastMcpServer(
                catalog = selected.map { catalog.getValue(it.name.value) },
                invoke = invokeCanonical,
                root = { FilesystemCanonicalRootDiscovery.discover(directory) },
                supplemental = investigation.tools,
                onInitialize = read::start,
            )
            .run(BufferedInputStream(System.`in`), System.out)
    }
}

internal class KastMcpServer(
    private val catalog: List<io.github.amichne.kast.cli.InstalledHostedToolDocument>,
    private val invoke: (String, JsonObject) -> CliExit,
    private val root: () -> CanonicalRootDiscovery,
    private val supplemental: List<McpSupplementalTool> = emptyList(),
    private val onInitialize: (CanonicalRoot) -> Refinement<Unit, DaemonOperationFailure> = {
        Refinement.Refined(Unit)
    },
    private val diagnostic: PrintStream = System.err,
) {
    private val tools = catalog.associateBy { it.name }
    private val supplementalByName = supplemental.associateBy { it.name }
    private var initialized = false

    @Suppress("LoopWithTooManyJumpStatements")
    fun run(input: BufferedInputStream, output: PrintStream) {
        while (true) {
            val line = readLine(input) ?: return
            val request = decodeRequest(line) ?: continue
            val id = request.id ?: continue
            val response =
                when (request.method) {
                    "initialize" -> initialize(id)
                    "ping" -> success(id, McpEmptyResult())
                    "tools/list" ->
                        success(
                            id,
                            McpToolList(
                                catalog.map { McpTool(it.name, it.description, it.inputSchema) } +
                                    supplemental.map { McpTool(it.name, it.description, it.inputSchema) }
                            ),
                        )
                    "tools/call" -> call(id, request.params)
                    else -> McpResponse(id, error = McpError(-32601, "method-not-found"))
                }
            output.println(mcpWire.encodeToString(response))
            output.flush()
        }
    }

    private fun initialize(id: JsonElement): McpResponse {
        if (!initialized) {
            initialized = true
            val outcome =
                when (val discovered = root()) {
                    is CanonicalRootDiscovery.Discovered -> onInitialize(discovered.root)
                    is CanonicalRootDiscovery.Rejected ->
                        Refinement.Rejected(DaemonOperationFailure.Root(discovered.failure))
                }
            report(
                "workspace",
                McpCallStage.PREPARATION,
                if (outcome is Refinement.Refined) McpCallOutcome.STARTED else McpCallOutcome.REJECTED,
            )
        }
        return success(id, McpInitializeResult())
    }

    private fun decodeRequest(line: String): McpRequest? =
        try {
            mcpWire.decodeFromString<McpRequest>(line).takeIf { it.jsonrpc == "2.0" }
        } catch (_: SerializationException) {
            null
        }

    private fun call(id: JsonElement, params: JsonElement?): McpResponse {
        val call =
            try {
                mcpWire.decodeFromJsonElement<McpToolCall>(
                    params ?: return rejected(id, McpCallFailure.INVALID_ARGUMENTS)
                )
            } catch (_: SerializationException) {
                return rejected(id, McpCallFailure.INVALID_ARGUMENTS)
            }
        if (call.name !in tools && call.name !in supplementalByName) return rejected(id, McpCallFailure.UNKNOWN_TOOL)
        report(call.name, McpCallStage.ADMISSION, McpCallOutcome.STARTED)
        if (root() !is CanonicalRootDiscovery.Discovered) {
            report(call.name, McpCallStage.ADMISSION, McpCallOutcome.REJECTED)
            return rejected(id, McpCallFailure.NOT_GRADLE_WORKSPACE)
        }
        // The existing CLI owns the schema, IDE identity, and semantic outcome.
        report(call.name, McpCallStage.EXECUTION, McpCallOutcome.STARTED)
        val exit =
            try {
                supplementalByName[call.name]?.invoke?.invoke(call.arguments) ?: invoke(call.name, call.arguments)
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
        val presentation = mcpReadPresentation(call.name, exit)
        return success(
            id,
            McpCallResult(
                content =
                    if (presentation == null) listOf(McpTextContent(exit.document.value))
                    else listOf(McpTextContent(presentation.summary), McpTextContent(exit.document.value)),
                isError = exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected,
                structuredContent = presentation?.envelope,
            ),
        )
    }

    private fun rejected(id: JsonElement, failure: McpCallFailure) =
        success(
            id,
            McpCallResult(
                listOf(McpTextContent(mcpWire.encodeToString(McpRejected(failure = failure)))),
                isError = true,
            ),
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

private val mcpWire = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private inline fun <reified T> success(id: JsonElement, result: T): McpResponse =
    McpResponse(id, mcpWire.encodeToJsonElement(result))

@Serializable
private data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
private data class McpResponse(
    val id: JsonElement,
    val result: JsonElement? = null,
    val error: McpError? = null,
    val jsonrpc: String = "2.0",
)

@Serializable
private data class McpInitializeResult(
    val protocolVersion: String = "2025-06-18",
    val capabilities: McpCapabilities = McpCapabilities(),
    val serverInfo: McpServerInfo = McpServerInfo(),
)

@Serializable private data class McpCapabilities(val tools: McpEmptyResult = McpEmptyResult())

@Serializable private data class McpServerInfo(val name: String = "kast", val version: String = "1")

@Serializable private class McpEmptyResult

@Serializable private data class McpToolList(val tools: List<McpTool>)

@Serializable private data class McpTool(val name: String, val description: String, val inputSchema: JsonElement)

@Serializable private data class McpToolCall(val name: String, val arguments: JsonObject)

@Serializable
private data class McpCallResult(
    val content: List<McpTextContent>,
    val isError: Boolean,
    /** Full machine envelope; the second text item retains the canonical response for older clients. */
    val structuredContent: JsonElement? = null,
)

@Serializable private data class McpTextContent(val text: String, val type: String = "text")

@Serializable private data class McpError(val code: Int, val message: String)

@Serializable private data class McpRejected(val status: String = "rejected", val failure: McpCallFailure)

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
private enum class McpCallStage {
    ADMISSION,
    PREPARATION,
    EXECUTION,
}

@Serializable
private enum class McpCallOutcome {
    STARTED,
    COMPLETED,
    REJECTED,
}
