package io.github.amichne.kast.server

import io.github.amichne.kast.api.ApiErrorResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal const val JSON_RPC_VERSION = "2.0"
internal const val JSON_RPC_PARSE_ERROR = -32700
internal const val JSON_RPC_INVALID_REQUEST = -32600
internal const val JSON_RPC_METHOD_NOT_FOUND = -32601
internal const val JSON_RPC_INTERNAL_ERROR = -32603
internal const val JSON_RPC_SERVER_ERROR_BASE = -32000

@Serializable
data class JsonRpcRequest(
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement = JsonNull,
    val jsonrpc: String = JSON_RPC_VERSION,
)

@Serializable
data class JsonRpcSuccessResponse(
    val result: JsonElement,
    val id: JsonElement = JsonNull,
    val jsonrpc: String = JSON_RPC_VERSION,
)

@Serializable
data class JsonRpcErrorObject(
    val code: Int,
    val message: String,
    val data: ApiErrorResponse? = null,
)

@Serializable
data class JsonRpcErrorResponse(
    val error: JsonRpcErrorObject,
    val id: JsonElement = JsonNull,
    val jsonrpc: String = JSON_RPC_VERSION,
)
