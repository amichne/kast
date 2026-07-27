package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.protocol.JSON_RPC_INTERNAL_ERROR
import io.github.amichne.kast.api.protocol.JSON_RPC_INVALID_REQUEST
import io.github.amichne.kast.api.protocol.JSON_RPC_METHOD_NOT_FOUND
import io.github.amichne.kast.api.protocol.JSON_RPC_PARSE_ERROR
import io.github.amichne.kast.api.protocol.JSON_RPC_SERVER_ERROR_BASE
import io.github.amichne.kast.api.protocol.JSON_RPC_VERSION
import io.github.amichne.kast.api.protocol.JsonRpcErrorObject
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.server.dispatch.RpcMethodRouter
import io.github.amichne.kast.server.dispatch.UnknownRpcMethodException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.Closeable
import java.util.UUID

class RpcAnalysisDispatcher(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
    private val projectOpenController: RuntimeProjectOpenController = RuntimeProjectOpenController.Unavailable,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    },
) : Closeable {
    private val methodRouter = RpcMethodRouter(
        backend = backend,
        config = config,
        lifecycleController = lifecycleController,
        projectOpenController = projectOpenController,
        json = json,
    )

    suspend fun dispatch(request: JsonRpcRequest): String {
        if (request.jsonrpc != JSON_RPC_VERSION || request.method.isBlank()) {
            return json.encodeToString(
                JsonRpcErrorResponse(
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_INVALID_REQUEST,
                        message = "Invalid JSON-RPC request",
                    ),
                    id = request.id,
                ),
            )
        }

        return try {
            val result = withTimeout(config.effectiveRequestTimeoutMillis) {
                methodRouter.dispatch(request.method, request.params)
            }
            json.encodeToString(
                JsonRpcSuccessResponse(
                    id = request.id,
                    result = result,
                ),
            )
        } catch (exception: AnalysisException) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = exception.toJsonRpcError(request.id),
                ),
            )
        } catch (exception: UnknownRpcMethodException) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_METHOD_NOT_FOUND,
                        message = exception.message ?: "Unknown JSON-RPC method",
                    ),
                ),
            )
        } catch (_: TimeoutCancellationException) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = timeoutJsonRpcError(request, config.effectiveRequestTimeoutMillis),
                ),
            )
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) throw exception
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = timeoutJsonRpcError(request, config.effectiveRequestTimeoutMillis),
                ),
            )
        } catch (exception: Throwable) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_INTERNAL_ERROR,
                        message = exception.message ?: exception::class.java.simpleName,
                        data = ApiErrorResponse(
                            requestId = requestId(request.id),
                            code = "INTERNAL_ERROR",
                            message = exception.message ?: exception::class.java.simpleName,
                            retryable = false,
                        ),
                    ),
                ),
            )
        }
    }

    suspend fun dispatchRaw(requestText: String): String {
        val request = runCatching {
            json.decodeFromString(JsonRpcRequest.serializer(), requestText)
        }.getOrElse { exception ->
            return json.encodeToString(
                JsonRpcErrorResponse(
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_PARSE_ERROR,
                        message = exception.message ?: "Failed to parse JSON-RPC request",
                    ),
                ),
            )
        }
        return dispatch(request)
    }

    internal fun runAfterResponseActions(): Boolean = methodRouter.runAfterResponseActions()

    override fun close() {
        methodRouter.close()
    }
}

private fun timeoutJsonRpcError(
    request: JsonRpcRequest,
    timeoutMillis: Long,
): JsonRpcErrorObject = JsonRpcErrorObject(
    code = JSON_RPC_SERVER_ERROR_BASE - REQUEST_TIMEOUT_STATUS_CODE,
    message = "Request timed out after ${timeoutMillis}ms",
    data = ApiErrorResponse(
        requestId = requestId(request.id),
        code = "TIMEOUT",
        message = "Request timed out after ${timeoutMillis}ms",
        retryable = true,
        details = mapOf(
            "method" to request.method,
            "timeoutMillis" to timeoutMillis.toString(),
        ),
    ),
)

private fun AnalysisException.toJsonRpcError(id: JsonElement): JsonRpcErrorObject = JsonRpcErrorObject(
    code = JSON_RPC_SERVER_ERROR_BASE - statusCode,
    message = message,
    data = ApiErrorResponse(
        requestId = requestId(id),
        code = errorCode,
        message = message,
        retryable = retryable,
        details = details,
    ),
)

private fun requestId(id: JsonElement): String =
    id.toString().takeIf { candidate ->
        candidate.isNotBlank() && candidate != JsonNull.toString()
    } ?: UUID.randomUUID().toString()

private const val REQUEST_TIMEOUT_STATUS_CODE = 504
