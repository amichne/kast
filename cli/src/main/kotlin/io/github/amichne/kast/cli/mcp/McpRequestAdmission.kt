package io.github.amichne.kast.cli.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Request identifiers are JSON-RPC-defined opaque scalar values, preserved verbatim at this boundary. */
internal sealed interface McpRequestAdmission {
    data class Call(val request: McpRequest, val id: JsonElement) : McpRequestAdmission

    data class Notification(val request: McpRequest) : McpRequestAdmission

    data class Rejected(val id: JsonElement, val failure: McpRequestFailure) : McpRequestAdmission {
        val error: McpError
            get() = McpError(failure.code, failure.message)
    }
}

internal enum class McpRequestFailure(val code: Int, val message: String) {
    PARSE_ERROR(JSON_RPC_PARSE_ERROR_CODE, "parse-error"),
    INVALID_REQUEST(JSON_RPC_INVALID_REQUEST_CODE, "invalid-request"),
}

private const val JSON_RPC_PARSE_ERROR_CODE = -32700
private const val JSON_RPC_INVALID_REQUEST_CODE = -32600

/** Admit the envelope before decoding it; an absent identifier and an explicit null are different requests. */
internal fun admitMcpRequest(line: String): McpRequestAdmission {
    val parsed =
        try {
            // JsonElement parsing alone permits unquoted primitive tokens; strict syntax admission comes first.
            requestSyntaxMapper.readTree(line)
            mcpWire.parseToJsonElement(line)
        } catch (_: JacksonException) {
            return McpRequestAdmission.Rejected(JsonNull, McpRequestFailure.PARSE_ERROR)
        } catch (_: SerializationException) {
            return McpRequestAdmission.Rejected(JsonNull, McpRequestFailure.PARSE_ERROR)
        }
    val raw = parsed as? JsonObject ?: return invalidRequest(JsonNull)
    val id = raw["id"] ?: JsonNull
    if (!validRequestId(id)) return invalidRequest(JsonNull)
    if (!validEnvelope(raw)) return invalidRequest(id)
    val request =
        try {
            // Unknown metadata remains compatible; the required JSON-RPC envelope was admitted above.
            mcpWire.decodeFromJsonElement<McpRequest>(raw)
        } catch (_: SerializationException) {
            return invalidRequest(id)
        }
    return if ("id" in raw) McpRequestAdmission.Call(request, id) else McpRequestAdmission.Notification(request)
}

private fun invalidRequest(id: JsonElement) = McpRequestAdmission.Rejected(id, McpRequestFailure.INVALID_REQUEST)

private fun validEnvelope(raw: JsonObject): Boolean {
    val version = raw["jsonrpc"] as? JsonPrimitive
    if (version == null || !version.isString || version.content != "2.0") return false
    val method = raw["method"] as? JsonPrimitive
    if (method == null || !method.isString) return false
    return "params" !in raw || raw["params"] is JsonObject || raw["params"] is JsonArray
}

private fun validRequestId(id: JsonElement): Boolean =
    when (id) {
        JsonNull -> true
        is JsonPrimitive -> id.isString || JSON_RPC_NUMBER.matches(id.content)
        is JsonArray,
        is JsonObject -> false
    }

private val JSON_RPC_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private val requestSyntaxMapper =
    JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
