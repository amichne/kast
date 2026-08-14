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
import io.github.amichne.kast.server.dispatch.RpcMethodResult
import io.github.amichne.kast.server.dispatch.RpcRequestWaitPolicy
import io.github.amichne.kast.server.dispatch.UnknownRpcMethodException
import io.github.amichne.kast.server.change.VerifiedAddDeclarationBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.Closeable
import io.github.amichne.kast.api.contract.RuntimeCapabilityLeaseKind
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RpcAnalysisDispatcher(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val publicSymbolReads: PublicSymbolReadBinding =
        PublicSymbolReadBinding.LegacyAnalysisBackend,
    private val verifiedAddDeclarations: VerifiedAddDeclarationBinding =
        VerifiedAddDeclarationBinding.Unavailable,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    },
) : Closeable {
    private val methodRouter = RpcMethodRouter(
        backend = backend,
        config = config,
        publicSymbolReads = publicSymbolReads,
        verifiedAddDeclarations = verifiedAddDeclarations,
        json = json,
    )
    private val lifecycleLock = ReentrantLock()
    private val quiescent = lifecycleLock.newCondition()
    private val activeDispatches = mutableSetOf<Job>()
    private var accepting = true
    private var closeStarted = false

    suspend fun dispatch(request: JsonRpcRequest): String = dispatchForTransport(request).response

    internal suspend fun dispatchForTransport(request: JsonRpcRequest): RpcDispatchResult =
        withDispatchAdmission(request.method) {
            dispatchAdmitted(request)
        }

    private suspend fun dispatchAdmitted(request: JsonRpcRequest): RpcDispatchResult {
        if (request.jsonrpc != JSON_RPC_VERSION || request.method.isBlank()) {
            return RpcDispatchResult(
                response = json.encodeToString(
                    JsonRpcErrorResponse(
                        error = JsonRpcErrorObject(
                            code = JSON_RPC_INVALID_REQUEST,
                            message = "Invalid JSON-RPC request",
                        ),
                        id = request.id,
                    ),
                ),
            )
        }

        return try {
            when (val routed = dispatchRouted(request)) {
                is RpcRoutedDispatch.Completed -> RpcDispatchResult(
                    response = json.encodeToString(
                        JsonRpcSuccessResponse(
                            id = request.id,
                            result = routed.result.result,
                        ),
                    ),
                )

                is RpcRoutedDispatch.DeadlineExceeded -> RpcDispatchResult(
                    response = json.encodeToString(
                        JsonRpcErrorResponse(
                            id = request.id,
                            error = timeoutJsonRpcError(request, routed.deadline.timeoutMillis),
                        ),
                    ),
                )
            }
        } catch (exception: AnalysisException) {
            RpcDispatchResult(
                response = json.encodeToString(
                    JsonRpcErrorResponse(
                        id = request.id,
                        error = exception.toJsonRpcError(request.id),
                    ),
                ),
            )
        } catch (exception: UnknownRpcMethodException) {
            RpcDispatchResult(
                response = json.encodeToString(
                    JsonRpcErrorResponse(
                        id = request.id,
                        error = JsonRpcErrorObject(
                            code = JSON_RPC_METHOD_NOT_FOUND,
                            message = exception.message ?: "Unknown JSON-RPC method",
                        ),
                    ),
                ),
            )
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) throw exception
            RpcDispatchResult(
                response = json.encodeToString(
                    JsonRpcErrorResponse(
                        id = request.id,
                        error = timeoutJsonRpcError(request, config.effectiveRequestTimeoutMillis),
                    ),
                ),
            )
        } catch (exception: Throwable) {
            RpcDispatchResult(
                response = json.encodeToString(
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
                ),
            )
        }
    }

    /**
     * Effect transition: `JsonRpcRequest -> RpcRoutedDispatch`.
     *
     * Ordinary and transition-aware methods convert coroutine timeout into
     * finite deadline data. Operations whose complete dispatch is governed by
     * backend progress cannot be cancelled by the unrelated ordinary budget.
     */
    private suspend fun dispatchRouted(request: JsonRpcRequest): RpcRoutedDispatch =
        when (val policy = RpcRequestWaitPolicy.derive(request.method, config)) {
            RpcRequestWaitPolicy.BackendProgressDeadline ->
                RpcRoutedDispatch.Completed(methodRouter.dispatch(request.method, request.params))

            is RpcRequestWaitPolicy.ServerDeadline -> try {
                RpcRoutedDispatch.Completed(
                    withTimeout(policy.timeoutMillis) {
                        methodRouter.dispatch(request.method, request.params)
                    },
                )
            } catch (_: TimeoutCancellationException) {
                RpcRoutedDispatch.DeadlineExceeded(policy)
            }
        }

    suspend fun dispatchRaw(requestText: String): String = dispatchRawForTransport(requestText).response

    internal suspend fun dispatchRawForTransport(requestText: String): RpcDispatchResult {
        val request = runCatching {
            json.decodeFromString(JsonRpcRequest.serializer(), requestText)
        }.getOrElse { exception ->
            return RpcDispatchResult(
                response = json.encodeToString(
                    JsonRpcErrorResponse(
                        error = JsonRpcErrorObject(
                            code = JSON_RPC_PARSE_ERROR,
                            message = exception.message ?: "Failed to parse JSON-RPC request",
                        ),
                    ),
                ),
            )
        }
        return dispatchForTransport(request)
    }

    override fun close() {
        val admitted = lifecycleLock.withLock {
            if (closeStarted) return
            closeStarted = true
            accepting = false
            activeDispatches.toList()
        }
        admitted.forEach { job ->
            job.cancel(CancellationException("Analysis server is shutting down"))
        }
        lifecycleLock.withLock {
            while (activeDispatches.isNotEmpty()) {
                quiescent.awaitUninterruptibly()
            }
        }
        methodRouter.close()
    }

    private suspend fun <T> withDispatchAdmission(
        method: String,
        block: suspend () -> T,
    ): T {
        val job = currentCoroutineContext()[Job]
                  ?: error("RPC dispatch requires a coroutine job")
        lifecycleLock.withLock {
            if (!accepting) {
                throw CancellationException("Analysis server is shutting down")
            }
            activeDispatches += job
        }
        val lease = if (method in READ_ONLY_OBSERVATION_METHODS) null else
            config.runtimeCapabilityLeases?.acquire(RuntimeCapabilityLeaseKind.REQUEST)
        return try {
            block()
        } finally {
            lease?.close()
            lifecycleLock.withLock {
                activeDispatches -= job
                if (activeDispatches.isEmpty()) {
                    quiescent.signalAll()
                }
            }
        }
    }

    private companion object {
        val READ_ONLY_OBSERVATION_METHODS = setOf("runtime/status", "health")
    }
}

private sealed interface RpcRoutedDispatch {
    data class Completed(val result: RpcMethodResult) : RpcRoutedDispatch

    data class DeadlineExceeded(
        val deadline: RpcRequestWaitPolicy.ServerDeadline,
    ) : RpcRoutedDispatch
}

internal class RpcDispatchResult(
    val response: String,
)

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
