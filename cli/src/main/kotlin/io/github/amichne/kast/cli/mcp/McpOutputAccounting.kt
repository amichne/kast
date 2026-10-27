package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOriginalOutcome
import io.github.amichne.kast.protocol.wire.presentation.querySemanticHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** The entire JSON-RPC response plus its LF frame. This allowance is separate from canonical maxReturnedBytes. */
internal const val MCP_RESPONSE_LIMIT_BYTES: Int = 4 * 1024 * 1024

private val headerJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun encodeBoundedMcpResponse(response: McpResponse): String {
    val encoded = mcpWire.encodeToString(McpResponse.serializer(), response)
    if (encoded.toByteArray(Charsets.UTF_8).size + 1 <= MCP_RESPONSE_LIMIT_BYTES) return encoded
    val result = response.result as? JsonObject
    val document = result?.get("structuredContent") as? JsonObject ?: emptyMcpArguments
    val outcome =
        when ((document["status"] as? JsonPrimitive)?.contentOrNull) {
            "complete" -> QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE
            "qualified" -> QuerySemanticHeaderDocumentOriginalOutcome.QUALIFIED
            else -> QuerySemanticHeaderDocumentOriginalOutcome.REJECTED_DOCUMENT
        }
    val limit = McpTransportRejection.OutputLimit(MCP_RESPONSE_LIMIT_BYTES, querySemanticHeader(document, outcome))
    val structured = headerJson.encodeToJsonElement(McpTransportRejection.serializer(), limit).jsonObject
    val control = success(response.id, McpCallResult(listOf(McpTextContent(structured.toString())), true, structured))
    val bounded = mcpWire.encodeToString(McpResponse.serializer(), control)
    check(bounded.toByteArray(Charsets.UTF_8).size + 1 <= MCP_RESPONSE_LIMIT_BYTES) {
        "Finite MCP control exceeded its protocol allowance"
    }
    return bounded
}
