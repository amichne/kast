package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.BrokerSessionHub
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import java.nio.file.Path
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal enum class NativeDecision {
    ACCEPT,
    DECLINE,
    CANCEL,
    MALFORMED,
}

internal data class NativeApprovalPreview(val changes: JsonArray)

internal sealed interface NativeToolResult {
    data class Document(val payload: JsonObject, val success: NativeToolSuccess) : NativeToolResult

    data object BrokerRejected : NativeToolResult

    data object ResponseLost : NativeToolResult

    fun document(): JsonObject =
        when (this) {
            is Document -> payload
            BrokerRejected,
            ResponseLost -> throw NativeRejected(NativeFailure.RESULT_SHAPE_REJECTED)
        }

    fun rejected(): Boolean =
        when (this) {
            is Document -> success == NativeToolSuccess.FAILED || payload["status"] == JsonPrimitive("rejected")
            BrokerRejected,
            ResponseLost -> true
        }
}

internal enum class NativeToolSuccess {
    SUCCEEDED,
    FAILED,
}

private enum class NativeControllerProgress {
    CONTINUE,
    RESPONSE_LOST,
}

private sealed interface NativeControllerFrame {
    data class Response(val raw: String) : NativeControllerFrame

    data class Notification(val raw: String) : NativeControllerFrame
}

internal sealed interface NativeApprovalAftermath {
    data object Continue : NativeApprovalAftermath

    data object DropResponse : NativeApprovalAftermath

    class Interrupt(val action: suspend () -> Unit) : NativeApprovalAftermath
}

private class NativeCallOptions(
    val decision: NativeDecision,
    val beforeApproval: suspend (NativeApprovalPreview) -> Unit,
    val aftermath: NativeApprovalAftermath,
)

/** Scripted transport only. Every request still crosses the production session, schema and provider boundaries. */
internal class NativeChangePeer(
    val session: BrokerSessionHub.Session,
    private val upstream: NativeChangeSession.NativeUpstream,
    val thread: String,
    private val trace: NativeProcessTrace,
    private val privateDirectory: Path,
    private val nextSequence: () -> Int,
) {
    suspend fun forwarded(): String =
        withTimeout(10_000) {
            select {
                upstream.sent.onReceive { it }
                session.output.onReceive { message ->
                    privateWrite(
                        privateDirectory.resolve("handshake-rejected-${nextSequence()}.private.json"),
                        protocolObservation(message).toString(),
                    )
                    throw NativeRejected(NativeFailure.PROTOCOL_REJECTED)
                }
            }
        }

    suspend fun call(
        tool: String,
        arguments: JsonObject,
        decision: NativeDecision = NativeDecision.ACCEPT,
        beforeApproval: suspend (NativeApprovalPreview) -> Unit = {},
        aftermath: NativeApprovalAftermath = NativeApprovalAftermath.Continue,
    ): NativeToolResult {
        val number = nextSequence()
        val request = request(number, tool, arguments).toString()
        privateWrite(privateDirectory.resolve("request-$number.json"), protocolObservation(request).toString())
        upstream.received.send(BrokerUpstreamFrame.Text(request))
        return withTimeout(180_000) {
            PendingCall(
                    number,
                    NativeCallOptions(
                        decision = decision,
                        beforeApproval = beforeApproval,
                        aftermath = aftermath,
                    ),
                )
                .await()
        }
    }

    private fun request(number: Int, tool: String, arguments: JsonObject): JsonObject = buildJsonObject {
        put("id", number)
        put("method", "item/tool/call")
        putJsonObject("params") {
            put("threadId", thread)
            put("turnId", "turn-$number")
            put("callId", "call-$number")
            put("namespace", "kast")
            put("tool", tool)
            put("arguments", arguments)
        }
    }

    private inner class PendingCall(private val number: Int, private val options: NativeCallOptions) {
        private var preview: NativeApprovalPreview? = null

        suspend fun await(): NativeToolResult {
            while (true) {
                when (val frame = nextFrame()) {
                    is NativeControllerFrame.Response -> {
                        val document = Json.parseToJsonElement(frame.raw).jsonObject
                        demand(document["id"] == JsonPrimitive(number), NativeFailure.PROTOCOL_REJECTED)
                        privateWrite(
                            privateDirectory.resolve("response-$number.json"),
                            protocolObservation(frame.raw).toString(),
                        )
                        return decode(document)
                    }
                    is NativeControllerFrame.Notification ->
                        if (
                            notification(Json.parseToJsonElement(frame.raw).jsonObject) ==
                                NativeControllerProgress.RESPONSE_LOST
                        )
                            return NativeToolResult.ResponseLost
                }
            }
        }

        private suspend fun notification(document: JsonObject): NativeControllerProgress {
            when (document["method"]?.jsonPrimitive?.content) {
                "item/started" ->
                    preview =
                        NativeApprovalPreview(
                            document.objectAt("params").objectAt("item")["changes"] as? JsonArray
                                ?: throw NativeRejected(NativeFailure.PROTOCOL_REJECTED)
                        )
                "item/fileChange/requestApproval" -> return approve(document)
                "serverRequest/resolved",
                "item/completed" -> Unit
                else -> throw NativeRejected(NativeFailure.PROTOCOL_REJECTED)
            }
            return NativeControllerProgress.CONTINUE
        }

        private suspend fun approve(document: JsonObject): NativeControllerProgress {
            options.beforeApproval(preview ?: throw NativeRejected(NativeFailure.PROTOCOL_REJECTED))
            session.accept(
                buildJsonObject {
                    put("id", document.getValue("id"))
                    putJsonObject("result") {
                        put(
                            "decision",
                            when (options.decision) {
                                NativeDecision.ACCEPT,
                                NativeDecision.MALFORMED -> "accept"
                                NativeDecision.DECLINE -> "decline"
                                NativeDecision.CANCEL -> "cancel"
                            },
                        )
                        if (options.decision == NativeDecision.MALFORMED) put("approved", true)
                    }
                }
                    .toString()
            )
            return when (val aftermath = options.aftermath) {
                NativeApprovalAftermath.Continue -> NativeControllerProgress.CONTINUE
                is NativeApprovalAftermath.Interrupt -> {
                    aftermath.action()
                    NativeControllerProgress.CONTINUE
                }
                NativeApprovalAftermath.DropResponse -> {
                    session.detach()
                    trace.completedEffects.receive()
                    NativeControllerProgress.RESPONSE_LOST
                }
            }
        }
    }

    private suspend fun nextFrame(): NativeControllerFrame = select {
        upstream.sent.onReceive { NativeControllerFrame.Response(it) }
        session.output.onReceive { NativeControllerFrame.Notification(it) }
    }

    private fun decode(document: JsonObject): NativeToolResult {
        val result = document["result"] as? JsonObject ?: return NativeToolResult.BrokerRejected
        val texts =
            (result["contentItems"] as? JsonArray).orEmpty().mapNotNull { item ->
                (item as? JsonObject)?.get("text") as? JsonPrimitive
            }
        val payload =
            texts
                .mapNotNull { text ->
                    runCatching { Json.parseToJsonElement(text.content) as? JsonObject }.getOrNull()
                }
                .singleOrNull { it.containsKey("document") }
                ?.get("document") as? JsonObject ?: return NativeToolResult.BrokerRejected
        val success =
            if (result["success"] == JsonPrimitive(true)) NativeToolSuccess.SUCCEEDED else NativeToolSuccess.FAILED
        return NativeToolResult.Document(payload, success)
    }
}
