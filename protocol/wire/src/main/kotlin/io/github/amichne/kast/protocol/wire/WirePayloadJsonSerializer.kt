package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/** Payload parser failures keep the role established by the generated envelope body. */
internal class WirePayloadDecodingException(val role: WireValueRole) : SerializationException("Wire payload rejected")

internal sealed class WirePayloadJsonSerializer(private val role: WireValueRole) : KSerializer<JsonElement> {
    private val delegate = JsonElement.serializer()
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): JsonElement =
        try {
            delegate.deserialize(decoder)
        } catch (_: SerializationException) {
            throw WirePayloadDecodingException(role)
        }

    override fun serialize(encoder: Encoder, value: JsonElement) = delegate.serialize(encoder, value)
}

internal object WireRequestJsonSerializer : WirePayloadJsonSerializer(WireValueRole.REQUEST)

internal object WireResultJsonSerializer : WirePayloadJsonSerializer(WireValueRole.RESULT)

internal object WireRejectionJsonSerializer : WirePayloadJsonSerializer(WireValueRole.REJECTION)
