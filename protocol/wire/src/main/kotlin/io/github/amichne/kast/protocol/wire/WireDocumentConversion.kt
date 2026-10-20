package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WireDocumentConversion
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Factory that binds generated kotlinx serializers to domain-owned protocol values. */
internal class GeneratedWireCodecFactory(private val json: Json) {
    fun <Value, Document> create(
        serializer: KSerializer<Document>,
        toDocument: (Value) -> Document,
        toValue: (Document) -> WireDocumentConversion<Value>,
        validateValue: (Value) -> Refinement<Unit, WireFailure> = { Refinement.Refined(Unit) },
    ): WireValueCodec<Value> =
        WireValueCodec(
            descriptors = listOf(serializer.descriptor),
            validateValue = validateValue,
            encodeValue = { value -> json.encodeToJsonElement(serializer, toDocument(value)) },
            decodeValue = { element -> toValue(json.decodeFromJsonElement(serializer, element)) },
        )

    /** Binds a generated serializer when the contract value is already the wire document. */
    fun <Value> create(serializer: KSerializer<Value>): WireValueCodec<Value> =
        create(
            serializer = serializer,
            toDocument = { value -> value },
            toValue = { document -> WireDocumentConversion.Converted(document) },
        )
}

/** Typed codec whose JSON structure is owned by one generated kotlinx serializer. */
internal class WireValueCodec<Value>
internal constructor(
    val descriptors: List<kotlinx.serialization.descriptors.SerialDescriptor>,
    private val encodeValue: (Value) -> JsonElement,
    private val decodeValue: (JsonElement) -> WireDocumentConversion<Value>,
    private val validateValue: (Value) -> Refinement<Unit, WireFailure> = { Refinement.Refined(Unit) },
) {
    fun encode(value: Value, role: WireValueRole): WireValueEncoding {
        when (val validation = validateValue(value)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return WireValueEncoding.Rejected(validation.failure)
        }
        return try {
            WireValueEncoding.Encoded(encodeValue(value))
        } catch (_: SerializationException) {
            WireValueEncoding.Rejected(WireFailure.PayloadEncodingFailed(role))
        }
    }

    /**
     * Proof transition: `JsonElement -> WireDecoding<Value>`.
     *
     * Establishes the exact generated document shape and a successful closed domain conversion.
     * [WireDocumentConversion.Rejected] and generated-decoder [SerializationException] both project to
     * [WireFailure.InvalidPayload]. Raw JSON remains inside this admitted-envelope boundary, so no conversion-detail
     * protocol is discarded.
     */
    fun decode(value: JsonElement, role: WireValueRole): WireDecoding<Value> {
        val conversion =
            try {
                decodeValue(value)
            } catch (_: SerializationException) {
                return WireDecoding.Rejected(WireFailure.InvalidPayload(role))
            }
        return when (conversion) {
            is WireDocumentConversion.Converted ->
                when (val validation = validateValue(conversion.value)) {
                    is Refinement.Refined -> WireDecoding.Decoded(conversion.value)
                    is Refinement.Rejected -> WireDecoding.Rejected(validation.failure)
                }
            is WireDocumentConversion.Rejected -> WireDecoding.Rejected(WireFailure.InvalidPayload(role))
        }
    }
}

internal sealed interface WireValueEncoding {
    data class Encoded(val value: JsonElement) : WireValueEncoding

    data class Rejected(val failure: WireFailure) : WireValueEncoding
}
