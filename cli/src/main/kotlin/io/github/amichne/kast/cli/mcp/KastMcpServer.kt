package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.DirectToolDocument
import io.github.amichne.kast.kernel.Refinement
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import java.io.BufferedInputStream
import java.io.PrintStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal class KastMcpServer(
    private val catalog: List<DirectToolDocument>,
    private val invoke: (String, JsonObject) -> CliExit,
    private val root: () -> CanonicalRootDiscovery,
    private val onInitialize: (CanonicalRoot) -> Refinement<Unit, DaemonOperationFailure> = {
        Refinement.Refined(Unit)
    },
    private val diagnostic: PrintStream = System.err,
) {
    private val tools = catalog.associateBy { it.name }
    private val report = McpCallReporter(diagnostic)
    private var era: McpProtocolEra? = null
    private var preparationStarted = false

    @Suppress("LoopWithTooManyJumpStatements")
    fun run(input: BufferedInputStream, output: PrintStream) {
        while (true) {
            val line = readMcpLine(input) ?: return
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
                else -> McpResponse(id, error = McpError(-32601, "method-not-found"))
            }
        }
        if (era == null) return McpResponse(id, error = McpError(-32602, "missing-request-meta"))
        return when (request.method) {
            "ping" -> success(id, McpEmptyResult())
            "tools/list" -> success(id, McpToolList(toolCatalog()))
            "tools/call" -> call(id, request.params, modern = false)
            else -> McpResponse(id, error = McpError(-32601, "method-not-found"))
        }
    }

    private fun toolCatalog(): List<McpTool> =
        catalog
            .map {
                McpTool(
                    it.name,
                    it.description,
                    requireObjectInputSchema(it.inputSchema),
                    it.outputSchema,
                    annotations =
                        ToolAnnotations(
                            readOnlyHint = it.readOnly,
                            destructiveHint = !it.readOnly,
                            idempotentHint = it.readOnly,
                            openWorldHint = false,
                        ),
                )
            }
            .sortedBy(McpTool::name)

    private fun requireObjectInputSchema(schema: JsonElement): JsonObject {
        val source = schema as? JsonObject ?: error("MCP tool input schema must be an object")
        require(source["type"] == JsonPrimitive("object")) { "MCP tool input schema must have an object root" }
        return source
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
        val raw = params as? JsonObject ?: return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
        if ("arguments" in raw && raw["arguments"] !is JsonObject)
            return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
        val call =
            try {
                mcpWire.decodeFromJsonElement<McpToolCall>(raw)
            } catch (_: SerializationException) {
                return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
            }
        if (call.name !in tools) return rejected(id, McpCallFailure.UNKNOWN_TOOL, modern)
        report(call.name, McpCallStage.ADMISSION, McpCallOutcome.STARTED)
        if (root() !is CanonicalRootDiscovery.Discovered) {
            report(call.name, McpCallStage.ADMISSION, McpCallOutcome.REJECTED)
            return rejected(id, McpCallFailure.NOT_GRADLE_WORKSPACE, modern)
        }
        // The existing CLI owns the schema, IDE identity, and semantic outcome.
        report(call.name, McpCallStage.EXECUTION, McpCallOutcome.STARTED)
        val exit =
            try {
                invoke(call.name, call.arguments ?: emptyMcpArguments)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                report(call.name, McpCallStage.EXECUTION, McpCallOutcome.REJECTED)
                return rejected(id, McpCallFailure.INVOCATION_FAILED, modern)
            }
        val canonical = runCatching { mcpWire.parseToJsonElement(exit.document.value) as? JsonObject }.getOrNull()
        val structured = if (exit is CliExit.BoundaryRejected) null else canonical
        val schemaEvidence =
            if (exit is CliExit.BoundaryRejected) null else McpStructuredResults.failure(call.name, structured)
        if (schemaEvidence != null) {
            report(
                call.name,
                McpCallStage.EXECUTION,
                McpCallOutcome.REJECTED,
                McpStructuredResults.variant(canonical),
                schemaEvidence.failure,
                schemaEvidence.field,
            )
            return rejected(id, McpCallFailure.INVALID_RESULT_SCHEMA, modern)
        }
        report(
            call.name,
            McpCallStage.EXECUTION,
            if (exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected) McpCallOutcome.REJECTED
            else McpCallOutcome.COMPLETED,
            McpStructuredResults.variant(structured),
        )
        val summary =
            when {
                modern && call.name == "health_check" -> McpStructuredResults.healthSummary(structured)
                else -> exit.document.value
            }
        return success(
            id,
            McpCallResult(
                content =
                    if (modern && call.name == "health_check") listOf(McpTextContent(summary))
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
}
