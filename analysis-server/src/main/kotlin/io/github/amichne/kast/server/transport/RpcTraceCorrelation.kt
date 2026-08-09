package io.github.amichne.kast.server

import io.github.amichne.kast.api.protocol.JSON_RPC_INVALID_REQUEST
import io.github.amichne.kast.api.protocol.JsonRpcErrorObject
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

public const val RPC_TRANSPORT_SPAN_NAME: String = "kast.transport.rpc"

/**
 * Carries the admitted cross-process trace identity.
 *
 * Construction is restricted to [parse] so consumers cannot exchange invocation,
 * parent, request, trace, or span identities. Raw extraction is permitted only at
 * the OpenTelemetry attribute and remote-parent boundary.
 */
public class RpcTraceCorrelation private constructor(
    private val invocationId: String,
    private val parentInvocationId: String,
    private val requestId: String,
    private val traceId: String,
    private val parentSpanId: String,
) {
    public fun invocationIdAtTelemetryBoundary(): String = invocationId

    public fun parentInvocationIdAtTelemetryBoundary(): String = parentInvocationId

    public fun requestIdAtTelemetryBoundary(): String = requestId

    public fun traceIdAtTelemetryBoundary(): String = traceId

    public fun parentSpanIdAtTelemetryBoundary(): String = parentSpanId

    internal companion object {
        /**
         * Proof transition: `(JsonElement, JsonElement) -> RpcTraceCorrelationParse`.
         *
         * [RpcTraceCorrelationParse.Accepted] establishes an exact field set, a
         * canonical UUID invocation, a hashed parent identity, a request identity
         * equal to the JSON-RPC id, and non-zero OpenTelemetry trace/span ids. The
         * closed expected failure is [RpcTraceCorrelationParse.Rejected]. Raw value
         * extraction is permitted only by the telemetry boundary methods above.
         */
        fun parse(
            element: JsonElement,
            jsonRpcId: JsonElement,
        ): RpcTraceCorrelationParse {
            val trace = element as? JsonObject
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.NOT_AN_OBJECT)
            if (trace.keys != TRACE_FIELDS) {
                return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.FIELD_SET_INVALID)
            }

            val invocationId = (trace["invocationId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf(INVOCATION_ID::matches)
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.INVOCATION_ID_INVALID)
            val parentInvocationId = (trace["parentInvocationId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf(PARENT_INVOCATION_ID::matches)
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.PARENT_ID_INVALID)
            val requestId = (trace["requestId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf(REQUEST_ID::matches)
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.REQUEST_ID_INVALID)
            val expectedRequestId = (jsonRpcId as? JsonPrimitive)
                ?.takeUnless { it === JsonNull || it.booleanOrNull != null }
                ?.content
                ?.takeIf(REQUEST_ID::matches)
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.REQUEST_ID_INVALID)
            if (requestId != expectedRequestId) {
                return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.REQUEST_ID_MISMATCH)
            }
            val traceId = (trace["traceId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf { TRACE_ID.matches(it) && it != ZERO_TRACE_ID }
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.TRACE_ID_INVALID)
            val parentSpanId = (trace["parentSpanId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf { SPAN_ID.matches(it) && it != ZERO_SPAN_ID }
                ?: return RpcTraceCorrelationParse.Rejected(RpcTraceCorrelationFailure.PARENT_SPAN_ID_INVALID)

            return RpcTraceCorrelationParse.Accepted(
                RpcTraceCorrelation(
                    invocationId = invocationId,
                    parentInvocationId = parentInvocationId,
                    requestId = requestId,
                    traceId = traceId,
                    parentSpanId = parentSpanId,
                ),
            )
        }
    }
}

public sealed interface RpcTraceState {
    public data object Absent : RpcTraceState

    public data class Active(val correlation: RpcTraceCorrelation) : RpcTraceState
}

public object RpcTraceContext {
    private val currentState = ThreadLocal.withInitial<RpcTraceState> { RpcTraceState.Absent }

    public fun current(): RpcTraceState = currentState.get()

    internal suspend fun <T> withCorrelation(
        correlation: RpcTraceCorrelation,
        block: suspend () -> T,
    ): T = withContext(currentState.asContextElement(RpcTraceState.Active(correlation))) {
        block()
    }
}

internal sealed interface RpcTraceCorrelationParse {
    data class Accepted(val correlation: RpcTraceCorrelation) : RpcTraceCorrelationParse

    data class Rejected(val failure: RpcTraceCorrelationFailure) : RpcTraceCorrelationParse
}

internal enum class RpcTraceCorrelationFailure {
    FIELD_SET_INVALID,
    INVOCATION_ID_INVALID,
    NOT_AN_OBJECT,
    PARENT_ID_INVALID,
    PARENT_SPAN_ID_INVALID,
    REQUEST_ID_INVALID,
    REQUEST_ID_MISMATCH,
    TRACE_ID_INVALID,
}

private sealed interface RpcTraceEnvelope {
    data class Correlated(
        val request: String,
        val correlation: RpcTraceCorrelation,
    ) : RpcTraceEnvelope

    data class Rejected(
        val requestId: JsonElement,
        val failure: RpcTraceCorrelationFailure,
    ) : RpcTraceEnvelope

    data class Uncorrelated(val request: String) : RpcTraceEnvelope
}

/**
 * Proof transition: `String -> RpcTraceEnvelope`.
 *
 * Establishes that an optional `kastTrace` envelope is either absent or fully
 * admitted as [RpcTraceCorrelation], and removes admitted transport metadata
 * before the stable JSON-RPC schema boundary. The closed expected failure is
 * [RpcTraceEnvelope.Rejected]. Raw JSON is released only to the dispatcher.
 */
private fun parseRpcTraceEnvelope(request: String): RpcTraceEnvelope {
    val root = runCatching { TRACE_JSON.parseToJsonElement(request) }.getOrNull() as? JsonObject
        ?: return RpcTraceEnvelope.Uncorrelated(request)
    val trace = root[TRACE_FIELD] ?: return RpcTraceEnvelope.Uncorrelated(request)
    val requestId = root["id"] ?: JsonNull
    return when (val parsed = RpcTraceCorrelation.parse(trace, requestId)) {
        is RpcTraceCorrelationParse.Accepted -> RpcTraceEnvelope.Correlated(
            request = JsonObject(root - TRACE_FIELD).toString(),
            correlation = parsed.correlation,
        )

        is RpcTraceCorrelationParse.Rejected -> RpcTraceEnvelope.Rejected(
            requestId = requestId,
            failure = parsed.failure,
        )
    }
}

/**
 * Proof transition: `String -> RpcDispatchResult` through `RpcTraceEnvelope`.
 *
 * A correlated request executes with one coroutine-propagated [RpcTraceCorrelation].
 * A malformed trace envelope becomes a finite JSON-RPC invalid-request response
 * and cannot reach backend dispatch. Raw request text is released only to the
 * existing dispatcher after this transition.
 */
internal suspend fun withRpcTraceCorrelation(
    request: String,
    dispatch: suspend (String) -> RpcDispatchResult,
): RpcDispatchResult = when (val envelope = parseRpcTraceEnvelope(request)) {
    is RpcTraceEnvelope.Uncorrelated -> dispatch(envelope.request)
    is RpcTraceEnvelope.Correlated -> RpcTraceContext.withCorrelation(envelope.correlation) {
        dispatch(envelope.request)
    }

    is RpcTraceEnvelope.Rejected -> RpcDispatchResult(
        response = TRACE_JSON.encodeToString(
            JsonRpcErrorResponse(
                id = envelope.requestId,
                error = JsonRpcErrorObject(
                    code = JSON_RPC_INVALID_REQUEST,
                    message = "Invalid trace correlation envelope: ${envelope.failure.name}",
                ),
            ),
        ),
    )
}

private const val TRACE_FIELD = "kastTrace"
private val TRACE_FIELDS = setOf("invocationId", "parentInvocationId", "requestId", "traceId", "parentSpanId")
private val INVOCATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val PARENT_INVOCATION_ID = Regex("[0-9a-f]{64}")
private val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val TRACE_ID = Regex("[0-9a-f]{32}")
private val SPAN_ID = Regex("[0-9a-f]{16}")
private const val ZERO_TRACE_ID = "00000000000000000000000000000000"
private const val ZERO_SPAN_ID = "0000000000000000"
private val TRACE_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}
