package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal sealed interface UpstreamEnvelope {
    data class Request(val method: String, val id: RpcId?) : UpstreamEnvelope

    data class Response(val id: RpcId) : UpstreamEnvelope

    data object Other : UpstreamEnvelope

    companion object {
        fun classify(document: JsonObject): UpstreamEnvelope {
            val method = document.string("method")
            if (method != null) return Request(method, RpcId.admit(document["id"]))
            val id = RpcId.admit(document["id"])
            return if (id != null && (document.containsKey("result") || document.containsKey("error"))) {
                Response(id)
            } else {
                Other
            }
        }

        internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    }
}

internal class RpcId
private constructor(
    val value: JsonPrimitive,
    val key: String,
) {
    companion object {
        fun admit(candidate: JsonElement?): RpcId? {
            val primitive = candidate as? JsonPrimitive ?: return null
            if (primitive.isString) return RpcId(primitive, "string:${primitive.content}")
            val numeric = primitive.content.toBigDecimalOrNull() ?: return null
            return RpcId(primitive, "number:${numeric.toPlainString()}")
        }
    }
}
