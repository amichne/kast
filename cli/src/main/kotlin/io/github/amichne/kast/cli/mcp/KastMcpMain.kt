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
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import java.io.BufferedInputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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
    private var era: McpProtocolEra? = null
    private var preparationStarted = false

    @Suppress("LoopWithTooManyJumpStatements")
    fun run(input: BufferedInputStream, output: PrintStream) {
        while (true) {
            val line = readLine(input) ?: return
            val request = decodeRequest(line) ?: continue
            val id = request.id ?: continue
            val response = dispatch(id, request)
            output.println(mcpWire.encodeToString(response))
            output.flush()
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "MagicNumber")
    private fun dispatch(id: JsonElement, request: McpRequest): McpResponse {
        if (request.method == "initialize") {
            if (era == McpProtocolEra.MODERN) return McpResponse(id, error = McpError(-32600, "modern-connection"))
            if (hasModernMeta(request.params))
                return McpResponse(id, error = McpError(-32600, "initialize-has-modern-envelope"))
            era = McpProtocolEra.LEGACY
            startPreparation()
            return success(id, McpInitializeResult())
        }
        val modern = hasModernMeta(request.params)
        if (era == McpProtocolEra.LEGACY && modern)
            return McpResponse(id, error = McpError(-32600, "legacy-connection"))
        if (era == McpProtocolEra.MODERN || modern) {
            if (era == McpProtocolEra.LEGACY) return McpResponse(id, error = McpError(-32600, "legacy-connection"))
            val meta =
                try {
                    mcpWire
                        .decodeFromJsonElement<McpModernParams>(
                            request.params ?: return McpResponse(id, error = McpError(-32602, "missing-request-meta"))
                        )
                        .meta
                } catch (_: SerializationException) {
                    return McpResponse(id, error = McpError(-32602, "missing-request-meta"))
                }
            if (meta.protocolVersion != MODERN_PROTOCOL_VERSION)
                return McpResponse(
                    id,
                    error =
                        McpError(
                            -32022,
                            "unsupported-protocol-version",
                            McpVersionErrorData(listOf(MODERN_PROTOCOL_VERSION), meta.protocolVersion),
                        ),
                )
            era = McpProtocolEra.MODERN
            startPreparation()
            return when (request.method) {
                "ping" -> success(id, McpEmptyResult(resultType = "complete"))
                "server/discover" -> success(id, McpDiscoverResult())
                "tools/list" ->
                    success(id, McpToolList(toolCatalog(), resultType = "complete", ttlMs = 0, cacheScope = "private"))
                "tools/call" -> call(id, request.params, modern = true)
                "resources/list" ->
                    success(id, McpResourceList(resultType = "complete", ttlMs = 0, cacheScope = "public"))
                "resources/read" -> readResource(id, request.params, modern = true)
                else -> McpResponse(id, error = McpError(-32601, "method-not-found"))
            }
        }
        if (era == null) return McpResponse(id, error = McpError(-32602, "missing-request-meta"))
        return when (request.method) {
            "ping" -> success(id, McpEmptyResult())
            "tools/list" -> success(id, McpToolList(toolCatalog()))
            "tools/call" -> call(id, request.params, modern = false)
            "resources/list" -> success(id, McpResourceList())
            "resources/read" -> readResource(id, request.params, modern = false)
            else -> McpResponse(id, error = McpError(-32601, "method-not-found"))
        }
    }

    private fun toolCatalog(): List<McpTool> =
        (catalog.map {
                val readOnly = it.effect == "read"
                McpTool(
                    it.name,
                    it.description,
                    objectInputSchema(it.inputSchema),
                    McpStructuredResults.schemaFor(it.name),
                    annotations =
                        ToolAnnotations(
                            readOnlyHint = readOnly,
                            destructiveHint = !readOnly,
                            idempotentHint = readOnly,
                            openWorldHint = false,
                        ),
                )
            } +
                supplemental.map {
                    McpTool(
                        it.name,
                        it.description,
                        objectInputSchema(it.inputSchema),
                        McpStructuredResults.schemaFor(it.name),
                        annotations =
                            ToolAnnotations(
                                readOnlyHint = true,
                                destructiveHint = false,
                                idempotentHint = true,
                                openWorldHint = false,
                            ),
                        meta = if (it.name == "validate_workspace") McpUiToolMeta() else null,
                    )
                })
            .sortedBy(McpTool::name)

    private fun objectInputSchema(schema: JsonElement): JsonObject {
        val source = schema as? JsonObject ?: error("MCP tool input schema must be an object")
        if (source["type"] != null) return source
        val variants = source["anyOf"] as? JsonArray ?: error("Unsupported MCP tool input schema")
        require(variants.isNotEmpty()) { "MCP tool input variants cannot be empty" }
        return mcpWire.encodeToJsonElement(McpObjectUnionInput(allOf = listOf(source))).jsonObject
    }

    @Suppress("MagicNumber")
    private fun readResource(id: JsonElement, params: JsonElement?, modern: Boolean): McpResponse {
        val request =
            try {
                mcpWire.decodeFromJsonElement<McpResourceReadRequest>(
                    params ?: return McpResponse(id, error = McpError(-32602, "missing-resource-uri"))
                )
            } catch (_: SerializationException) {
                return McpResponse(id, error = McpError(-32602, "invalid-resource-uri"))
            }
        if (request.uri != VALIDATION_UI_URI) return McpResponse(id, error = McpError(-32602, "unknown-resource-uri"))
        val html =
            KastMcpServer::class.java.getResourceAsStream("/mcp/validation.html")?.bufferedReader()?.use {
                it.readText()
            } ?: return McpResponse(id, error = McpError(-32603, "validation-view-unavailable"))
        return success(
            id,
            McpResourceRead(
                listOf(McpResourceContent(text = html)),
                resultType = if (modern) "complete" else null,
                ttlMs = if (modern) 3_600_000 else null,
                cacheScope = if (modern) "public" else null,
            ),
        )
    }

    private fun hasModernMeta(params: JsonElement?): Boolean {
        val meta = (params as? JsonObject)?.get("_meta") as? JsonObject ?: return false
        return "io.modelcontextprotocol/protocolVersion" in meta || "io.modelcontextprotocol/clientCapabilities" in meta
    }

    private fun startPreparation() {
        if (!preparationStarted) {
            preparationStarted = true
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
    }

    private fun decodeRequest(line: String): McpRequest? =
        try {
            mcpWire.decodeFromString<McpRequest>(line).takeIf { it.jsonrpc == "2.0" }
        } catch (_: SerializationException) {
            null
        }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    private fun call(id: JsonElement, params: JsonElement?, modern: Boolean): McpResponse {
        val call =
            try {
                mcpWire.decodeFromJsonElement<McpToolCall>(
                    params ?: return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
                )
            } catch (_: SerializationException) {
                return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
            }
        if (call.name !in tools && call.name !in supplementalByName)
            return rejected(id, McpCallFailure.UNKNOWN_TOOL, modern)
        report(call.name, McpCallStage.ADMISSION, McpCallOutcome.STARTED)
        if (root() !is CanonicalRootDiscovery.Discovered) {
            report(call.name, McpCallStage.ADMISSION, McpCallOutcome.REJECTED)
            return rejected(id, McpCallFailure.NOT_GRADLE_WORKSPACE, modern)
        }
        // The existing CLI owns the schema, IDE identity, and semantic outcome.
        report(call.name, McpCallStage.EXECUTION, McpCallOutcome.STARTED)
        val exit =
            try {
                supplementalByName[call.name]?.invoke?.invoke(call.arguments ?: emptyMcpArguments)
                    ?: invoke(call.name, call.arguments ?: emptyMcpArguments)
            } catch (_: RuntimeException) {
                report(call.name, McpCallStage.EXECUTION, McpCallOutcome.REJECTED)
                return rejected(id, McpCallFailure.INVOCATION_FAILED, modern)
            }
        val presentation = mcpReadPresentation(call.name, exit)
        val structured =
            (presentation?.envelope as? JsonObject)
                ?: runCatching {
                    mcpWire.parseToJsonElement(exit.document.value) as? JsonObject
                }
                    .getOrNull()
        if (structured == null || !McpStructuredResults.validates(call.name, structured)) {
            report(call.name, McpCallStage.EXECUTION, McpCallOutcome.REJECTED)
            return rejected(id, McpCallFailure.INVALID_RESULT_SCHEMA, modern)
        }
        report(
            call.name,
            McpCallStage.EXECUTION,
            if (exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected) McpCallOutcome.REJECTED
            else McpCallOutcome.COMPLETED,
        )
        val summary =
            when {
                presentation != null -> presentation.summary
                modern && call.name == "health_check" -> McpStructuredResults.healthSummary(structured)
                modern && call.name == "validate_workspace" -> McpStructuredResults.validationSummary(structured)
                else -> exit.document.value
            }
        return success(
            id,
            McpCallResult(
                content =
                    if (presentation != null) listOf(McpTextContent(summary), McpTextContent(exit.document.value))
                    else if (modern && call.name in setOf("health_check", "validate_workspace"))
                        listOf(McpTextContent(summary))
                    else listOf(McpTextContent(exit.document.value)),
                isError = exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected,
                structuredContent = structured,
                resultType = if (modern) "complete" else null,
            ),
        )
    }

    private fun rejected(id: JsonElement, failure: McpCallFailure, modern: Boolean): McpResponse {
        val rejection = McpRejected(error = McpCallError(failure, failure.nextAction))
        return success(
            id,
            McpCallResult(
                listOf(McpTextContent(mcpWire.encodeToString(rejection))),
                isError = true,
                structuredContent = mcpWire.encodeToJsonElement(rejection).jsonObject,
                resultType = if (modern) "complete" else null,
            ),
        )
    }

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
