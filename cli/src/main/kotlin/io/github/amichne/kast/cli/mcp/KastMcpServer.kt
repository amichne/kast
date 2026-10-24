package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.DirectToolDocument
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import java.io.BufferedInputStream
import java.io.PrintStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    fun run(input: BufferedInputStream, output: PrintStream) {
        val publicationLock = Any()
        fun publish(response: McpResponse) =
            synchronized(publicationLock) {
                output.println(mcpWire.encodeToString(response))
                output.flush()
            }
        val runner =
            McpRunnerInvocationOwner(
                publish = ::publish,
                cancelled = { tool -> report(tool, McpCallStage.EXECUTION, McpCallOutcome.CANCELLED) },
                failed = { tool ->
                    report(tool, McpCallStage.EXECUTION, McpCallOutcome.REJECTED)
                    try {
                        input.close()
                    } catch (_: java.io.IOException) {
                        // Closing the output still rejects the client's pending request.
                    } finally {
                        output.close()
                    }
                },
            )
        try {
            while (true) {
                val line = readMcpLine(input)
                if (line == null) return
                when (val admission = admitMcpRequest(line)) {
                    is McpRequestAdmission.Call -> receiveCall(admission, runner, ::publish)
                    is McpRequestAdmission.Notification -> runner.cancel(admission.request)
                    is McpRequestAdmission.Rejected -> publish(McpResponse(admission.id, error = admission.error))
                }
            }
        } finally {
            runner.stop()
        }
    }

    private fun receiveCall(
        admission: McpRequestAdmission.Call,
        runner: McpRunnerInvocationOwner,
        publish: (McpResponse) -> Unit,
    ) {
        when (val selected = dispatch(admission.id, admission.request)) {
            is McpDispatch.Response -> publish(selected.response)
            is McpDispatch.Invocation ->
                when {
                    runner.active ->
                        publish(rejected(selected.id, McpCallFailure.INVOCATION_IN_PROGRESS, selected.modern))
                    selected.policy == QueryExecutionModeDocument.RUNNER_CONTROLLED ->
                        runner.start(selected.id, selected.call.name) { invokeCall(selected) }
                    else -> publish(invokeCall(selected))
                }
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "MagicNumber")
    private fun dispatch(id: JsonElement, request: McpRequest): McpDispatch {
        if (request.method == "initialize") {
            if (era == McpProtocolEra.MODERN) return response(id, error = McpError(-32600, "modern-connection"))
            if (hasModernMeta(request.params))
                return response(id, error = McpError(-32600, "initialize-has-modern-envelope"))
            era = McpProtocolEra.LEGACY
            startPreparation()
            return respond(id, McpInitializeResult())
        }
        val modern = hasModernMeta(request.params)
        if (era == McpProtocolEra.LEGACY && modern) return response(id, error = McpError(-32600, "legacy-connection"))
        if (era == McpProtocolEra.MODERN || modern) {
            if (era == McpProtocolEra.LEGACY) return response(id, error = McpError(-32600, "legacy-connection"))
            val meta =
                try {
                    mcpWire
                        .decodeFromJsonElement<McpModernParams>(
                            request.params ?: return response(id, error = McpError(-32602, "missing-request-meta"))
                        )
                        .meta
                } catch (_: SerializationException) {
                    return response(id, error = McpError(-32602, "missing-request-meta"))
                }
            if (meta.protocolVersion != MODERN_PROTOCOL_VERSION)
                return response(
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
                "ping" -> respond(id, McpEmptyResult(resultType = "complete"))
                "server/discover" -> respond(id, McpDiscoverResult())
                "tools/list" ->
                    respond(id, McpToolList(toolCatalog(), resultType = "complete", ttlMs = 0, cacheScope = "private"))
                "tools/call" -> admitCall(id, request.params, modern = true)
                else -> response(id, error = McpError(-32601, "method-not-found"))
            }
        }
        if (era == null) return response(id, error = McpError(-32602, "missing-request-meta"))
        return when (request.method) {
            "ping" -> respond(id, McpEmptyResult())
            "tools/list" -> respond(id, McpToolList(toolCatalog()))
            "tools/call" -> admitCall(id, request.params, modern = false)
            else -> response(id, error = McpError(-32601, "method-not-found"))
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
                            // Every call can save editor buffers and refresh the project during preparation.
                            readOnlyHint = false,
                            destructiveHint = true,
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

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    private fun admitCall(id: JsonElement, params: JsonElement?, modern: Boolean): McpDispatch {
        val raw = params as? JsonObject ?: return responseRejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
        if ("arguments" in raw && raw["arguments"] !is JsonObject)
            return responseRejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
        val call =
            try {
                mcpWire.decodeFromJsonElement<McpToolCall>(raw)
            } catch (_: SerializationException) {
                return responseRejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
            }
        if (call.name !in tools) return responseRejected(id, McpCallFailure.UNKNOWN_TOOL, modern)
        report(call.name, McpCallStage.ADMISSION, McpCallOutcome.STARTED)
        if (root() !is CanonicalRootDiscovery.Discovered) {
            report(call.name, McpCallStage.ADMISSION, McpCallOutcome.REJECTED)
            return responseRejected(id, McpCallFailure.NOT_GRADLE_WORKSPACE, modern)
        }
        return McpDispatch.Invocation(id, call, modern, call.executionPolicy())
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    private fun invokeCall(invocation: McpDispatch.Invocation): McpResponse {
        val id = invocation.id
        val call = invocation.call
        val modern = invocation.modern
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
        if (exit is CliExit.BoundaryRejected || exit is CliExit.PublicInputRejected) {
            if (canonical == null) return rejected(id, McpCallFailure.INVALID_RESULT_SCHEMA, modern)
            report(call.name, McpCallStage.EXECUTION, McpCallOutcome.REJECTED, McpStructuredResults.variant(canonical))
            return transportRejected(id, McpTransportRejection.BoundaryFailure(canonical), modern)
        }
        val structured = canonical
        val schemaEvidence = McpStructuredResults.failure(call.name, structured)
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
        if (structured == null || !McpStructuredResults.matchesExit(exit, structured)) {
            report(
                call.name,
                McpCallStage.EXECUTION,
                McpCallOutcome.REJECTED,
                McpStructuredResults.variant(structured),
                McpResultSchemaFailure.OUTCOME_MISMATCH,
                McpResultSchemaField.STATUS,
            )
            return rejected(id, McpCallFailure.INVALID_RESULT_SCHEMA, modern)
        }
        report(
            call.name,
            McpCallStage.EXECUTION,
            if (exit is CliExit.OperationRejected) McpCallOutcome.REJECTED else McpCallOutcome.COMPLETED,
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
                isError = exit is CliExit.OperationRejected,
                structuredContent = structured,
                resultType = if (modern) "complete" else null,
            ),
        )
    }

    private inline fun <reified T> respond(id: JsonElement, result: T): McpDispatch =
        McpDispatch.Response(success(id, result))

    private fun response(id: JsonElement, error: McpError): McpDispatch =
        McpDispatch.Response(McpResponse(id, error = error))

    private fun responseRejected(id: JsonElement, failure: McpCallFailure, modern: Boolean): McpDispatch =
        McpDispatch.Response(rejected(id, failure, modern))

    private fun rejected(id: JsonElement, failure: McpCallFailure, modern: Boolean): McpResponse {
        return transportRejected(
            id,
            McpTransportRejection.CallFailure(McpCallError(failure, failure.nextAction)),
            modern,
        )
    }

    private fun transportRejected(id: JsonElement, rejection: McpTransportRejection, modern: Boolean): McpResponse {
        return success(
            id,
            McpCallResult(
                listOf(McpTextContent(mcpWire.encodeToString(McpTransportRejection.serializer(), rejection))),
                isError = true,
                structuredContent =
                    mcpWire.encodeToJsonElement(McpTransportRejection.serializer(), rejection).jsonObject,
                resultType = if (modern) "complete" else null,
            ),
        )
    }
}

private sealed interface McpDispatch {
    data class Response(val response: McpResponse) : McpDispatch

    data class Invocation(
        val id: JsonElement,
        val call: McpToolCall,
        val modern: Boolean,
        val policy: QueryExecutionModeDocument,
    ) : McpDispatch
}

/** Scheduling does not admit semantic input. The direct session owns its complete schema. */
private fun McpToolCall.executionPolicy(): QueryExecutionModeDocument =
    if (name == "query_symbols") {
        val request = arguments?.get("request") as? JsonObject
        if (
            request?.get("type") == JsonPrimitive("RUN") &&
                request["executionMode"] in listOf(null, JsonNull, JsonPrimitive("RUNNER_CONTROLLED"))
        )
            QueryExecutionModeDocument.RUNNER_CONTROLLED
        else QueryExecutionModeDocument.BOUNDED
    } else QueryExecutionModeDocument.BOUNDED
