package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import java.io.BufferedInputStream
import java.io.PrintStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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
                val effect = OperationEffect.entries.singleOrNull { value -> value.name.lowercase() == it.effect }
                    ?: error("Unknown canonical operation effect")
                val readOnly = effect == OperationEffect.NONE || effect == OperationEffect.INTELLIJ_READ
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
                                readOnlyHint = it.readOnly,
                                destructiveHint = !it.readOnly,
                                idempotentHint = it.readOnly,
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
        val raw = params as? JsonObject ?: return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
        if ("arguments" in raw && raw["arguments"] !is JsonObject)
            return rejected(id, McpCallFailure.INVALID_ARGUMENTS, modern)
        val call =
            try {
                mcpWire.decodeFromJsonElement<McpToolCall>(raw)
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
        val canonical =
            (presentation?.envelope as? JsonObject)
                ?: runCatching {
                    mcpWire.parseToJsonElement(exit.document.value) as? JsonObject
                }
                    .getOrNull()
        val hostedRejection =
            if (exit is CliExit.OperationRejected && canonical != null) McpStructuredResults.hostedRejection(canonical)
            else null
        val structured = hostedRejection?.let { mcpWire.encodeToJsonElement(it).jsonObject } ?: canonical
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
        report(
            call.name,
            McpCallStage.EXECUTION,
            if (exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected) McpCallOutcome.REJECTED
            else McpCallOutcome.COMPLETED,
            if (hostedRejection != null) McpResultVariant.HOST_REJECTED else McpStructuredResults.variant(structured),
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
}
