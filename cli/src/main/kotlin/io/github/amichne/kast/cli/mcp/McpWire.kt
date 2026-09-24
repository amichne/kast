package io.github.amichne.kast.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal val mcpWire = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
internal val emptyMcpArguments = mcpWire.encodeToJsonElement(McpEmptyObject()).jsonObject

internal inline fun <reified T> success(id: JsonElement, result: T): McpResponse =
    McpResponse(id, mcpWire.encodeToJsonElement(result))

@Serializable
internal data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

internal const val MODERN_PROTOCOL_VERSION = "2026-07-28"
internal const val VALIDATION_UI_URI = "ui://kast/validation"

internal enum class McpProtocolEra {
    LEGACY,
    MODERN,
}

@Serializable internal data class McpModernParams(@SerialName("_meta") val meta: McpRequestMeta)

@Serializable
internal data class McpRequestMeta(
    @SerialName("io.modelcontextprotocol/protocolVersion") val protocolVersion: String,
    @SerialName("io.modelcontextprotocol/clientCapabilities") val clientCapabilities: JsonObject,
)

@Serializable
internal data class McpResponse(
    val id: JsonElement,
    val result: JsonElement? = null,
    val error: McpError? = null,
    val jsonrpc: String = "2.0",
)

@Serializable
internal data class McpInitializeResult(
    val protocolVersion: String = "2025-06-18",
    val capabilities: McpCapabilities = McpCapabilities(),
    val serverInfo: McpServerInfo = McpServerInfo(),
    val instructions: String = KAST_MCP_INSTRUCTIONS,
)

private const val KAST_MCP_INSTRUCTIONS =
    "Kast reads the saved, indexed Kotlin/Gradle project in IntelliJ IDEA. " +
        "Call tools from the repository root. Each semantic call waits for bounded IDE preparation and indexing " +
        "before the read; a missing cached Gradle model triggers one linked-project reload. Wait for the response " +
        "and follow its finite recovery instruction if preparation rejects. Unsaved editor buffers block refresh. " +
        "Search classes or functions by name first, preserve returned symbol references verbatim, then use " +
        "read_relations with callees or callers for one semantic hop. " +
        "Relation destinations may be in other packages. " +
        "Read both the outer invocation status and inner semantic status: qualified results are a known minimum, " +
        "never proof of absence. Inspect limitations and omission reasons such as UNSUPPORTED_ITEM; resume only when " +
        "a continuation is returned, using the same subject and relation. " +
        "For BUDGET_EXCEEDED inspect the reported stage and execution_budget. A MODEL_CAPTURE timeout " +
        "precedes semantic search; a SEMANTIC_READ timeout may benefit from a narrower scope. " +
        "MODEL_CAPTURE_REJECTED happens before semantic search; " +
        "inspect the finite capture reason and the IDE's kast_semantic_read receipt."

@Serializable
internal data class McpCapabilities(
    val tools: McpEmptyObject = McpEmptyObject(),
    val resources: McpEmptyObject = McpEmptyObject(),
)

@Serializable internal data class McpServerInfo(val name: String = "kast", val version: String = "1")

@Serializable
internal data class McpDiscoverResult(
    val resultType: String = "complete",
    val supportedVersions: List<String> = listOf(MODERN_PROTOCOL_VERSION),
    val capabilities: McpCapabilities = McpCapabilities(),
    @SerialName("_meta") val meta: McpServerMeta = McpServerMeta(),
    val ttlMs: Int = 0,
    val cacheScope: String = "private",
)

@Serializable
internal data class McpServerMeta(
    @SerialName("io.modelcontextprotocol/serverInfo") val serverInfo: McpServerInfo = McpServerInfo()
)

@Serializable internal class McpEmptyObject

@Serializable
internal data class McpEmptyResult(
    val resultType: String? = null,
    @SerialName("_meta") val meta: McpServerMeta = McpServerMeta(),
)

@Serializable
internal data class McpToolList(
    val tools: List<McpTool>,
    val resultType: String? = null,
    val ttlMs: Int? = null,
    val cacheScope: String? = null,
    @SerialName("_meta") val meta: McpServerMeta = McpServerMeta(),
)

@Serializable
internal data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val outputSchema: JsonObject,
    val annotations: ToolAnnotations,
    @SerialName("_meta") val meta: McpUiToolMeta? = null,
)

@Serializable internal data class McpUiToolMeta(val ui: McpUiLink = McpUiLink())

@Serializable internal data class McpUiLink(val resourceUri: String = VALIDATION_UI_URI)

@Serializable
internal data class McpResourceList(
    val resources: List<McpResource> = listOf(McpResource()),
    val resultType: String? = null,
    val ttlMs: Int? = null,
    val cacheScope: String? = null,
    @SerialName("_meta") val meta: McpServerMeta = McpServerMeta(),
)

@Serializable
internal data class McpResource(
    val uri: String = VALIDATION_UI_URI,
    val name: String = "Kast workspace validation",
    val description: String = "Rendered status of the five workspace validation probes.",
    val mimeType: String = "text/html;profile=mcp-app",
)

@Serializable internal data class McpResourceReadRequest(val uri: String)

@Serializable
internal data class McpResourceRead(
    val contents: List<McpResourceContent>,
    val resultType: String? = null,
    val ttlMs: Int? = null,
    val cacheScope: String? = null,
    @SerialName("_meta") val meta: McpServerMeta = McpServerMeta(),
)

@Serializable
internal data class McpResourceContent(
    val uri: String = VALIDATION_UI_URI,
    val mimeType: String = "text/html;profile=mcp-app",
    val text: String,
)

@Serializable internal data class McpToolCall(val name: String, val arguments: JsonObject? = null)

/** The variant schemas are an already generated dynamic contract; the wrapper establishes MCP's object root. */
@Serializable internal data class McpObjectUnionInput(val type: String = "object", val allOf: List<JsonElement>)

@Serializable
internal data class McpCallResult(
    val content: List<McpTextContent>,
    val isError: Boolean,
    /** Full machine envelope; the second text item retains the canonical response for older clients. */
    val structuredContent: JsonObject? = null,
    val resultType: String? = null,
    @SerialName("_meta") val meta: McpServerMeta = McpServerMeta(),
)

@Serializable internal data class McpTextContent(val text: String, val type: String = "text")

@Serializable internal data class McpError(val code: Int, val message: String, val data: McpVersionErrorData? = null)

@Serializable internal data class McpVersionErrorData(val supported: List<String>, val requested: String)

@Serializable internal data class McpRejected(val status: String = "rejected", val error: McpCallError)

@Serializable
internal data class McpCallError(
    val code: McpCallFailure,
    val message: String,
    /** Schema-admitted host rejection, retained without reinterpreting its closed cause. */
    val evidence: JsonElement? = null,
)

@Serializable
internal enum class McpCallFailure {
    INVALID_ARGUMENTS,
    UNKNOWN_TOOL,
    NOT_GRADLE_WORKSPACE,
    INVOCATION_FAILED,
    HOSTED_OPERATION_REJECTED,
    INVALID_RESULT_SCHEMA;

    val nextAction: String
        get() =
            when (this) {
                INVALID_ARGUMENTS -> "Check this tool's inputSchema in tools/list and retry with valid arguments."
                UNKNOWN_TOOL -> "Call tools/list and choose a listed tool name."
                NOT_GRADLE_WORKSPACE -> "Start Kast MCP inside the intended Gradle workspace."
                INVOCATION_FAILED -> "Call health_check to inspect workspace readiness before retrying."
                HOSTED_OPERATION_REJECTED -> "Inspect the hosted rejection evidence for the specific cause."
                INVALID_RESULT_SCHEMA -> "Report the Kast result schema failure with the tool name."
            }
}

@Serializable
internal data class McpCallEvent(
    val component: String = "kast-mcp",
    val tool: String,
    val stage: McpCallStage,
    val outcome: McpCallOutcome,
    val resultVariant: McpResultVariant? = null,
    val schemaFailure: McpResultSchemaFailure? = null,
    val schemaField: McpResultSchemaField? = null,
)

@Serializable
internal enum class McpResultVariant {
    COMPLETE,
    PARTIAL,
    QUALIFIED,
    REJECTED,
    UNAVAILABLE,
    HOST_REJECTED,
    UNKNOWN,
}

@Serializable
internal enum class McpResultSchemaFailure {
    UNPARSEABLE_DOCUMENT,
    MISSING_STATUS,
    INVALID_STATUS_TYPE,
    SCHEMA_VIOLATION,
}

@Serializable
internal enum class McpResultSchemaField {
    STATUS,
    DATA,
    COVERAGE,
    BASIS,
    STOP_REASON,
    ERROR,
    UNKNOWN,
}

@Serializable
internal enum class McpCallStage {
    ADMISSION,
    PREPARATION,
    EXECUTION,
}

@Serializable
internal enum class McpCallOutcome {
    STARTED,
    COMPLETED,
    REJECTED,
}
