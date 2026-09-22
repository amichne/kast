package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Bounded protocol parsing and response projection; the request ID is a contract-defined opaque JSON value. */
internal class BrokerSessionMessages(private val maximumMessageBytes: Int) {
    fun parse(message: String): JsonObject? =
        if (message.toByteArray().size > maximumMessageBytes) null
        else
            try {
                Json.parseToJsonElement(message) as? JsonObject
            } catch (_: Exception) {
                null
            }

    fun rejectedInvocation(
        document: JsonObject,
        failure: InvocationFenceFailure,
    ): ProtocolRouting.ReplyUpstream =
        ProtocolRouting.ReplyUpstream(
            toolFailure(document, failure.name),
            if (failure == InvocationFenceFailure.OUTCOME_UNCERTAIN)
                io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty.UNCERTAIN
            else io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty.KNOWN,
        )

    fun unsubscribed(doc: JsonObject): String =
        Json.encodeToString(
            SubscriptionReply(doc["id"] ?: JsonNull, SubscriptionResult(SubscriptionStatus.UNSUBSCRIBED))
        )

    fun rejection(doc: JsonObject, reason: String): String =
        Json.encodeToString(RejectionReply(doc["id"] ?: JsonNull, RejectionError(SESSION_REJECTION_CODE, reason)))
}

@Serializable private data class RejectionReply(val id: JsonElement, val error: RejectionError)

@Serializable private data class RejectionError(val code: Int, val message: String)

private const val SESSION_REJECTION_CODE = -32040

@Serializable private data class SubscriptionReply(val id: JsonElement, val result: SubscriptionResult)

@Serializable private data class SubscriptionResult(val status: SubscriptionStatus)

@Serializable
private enum class SubscriptionStatus {
    @SerialName("unsubscribed") UNSUBSCRIBED
}
