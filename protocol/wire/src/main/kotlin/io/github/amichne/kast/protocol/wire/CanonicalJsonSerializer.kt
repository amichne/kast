package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun <Value> jsonContractSerializer(
    serialName: String,
    encode: (Value) -> JsonElement,
    decode: (JsonElement) -> Value,
): KSerializer<Value> = object : KSerializer<Value> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(
        encoder: Encoder,
        value: Value,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
                          ?: throw SerializationException("Canonical operation values require JSON")
        jsonEncoder.encodeJsonElement(encode(value))
    }

    override fun deserialize(decoder: Decoder): Value {
        val jsonDecoder = decoder as? JsonDecoder
                          ?: throw SerializationException("Canonical operation values require JSON")
        return decode(jsonDecoder.decodeJsonElement())
    }
}

internal inline fun <reified Value : Enum<Value>> canonicalEnumSerializer(
    serialName: String,
): KSerializer<Value> = jsonContractSerializer(
    serialName = serialName,
    encode = { JsonPrimitive(it.name.lowercase()) },
    decode = { element ->
        val name = element.stringValue().uppercase()
        try {
            enumValueOf<Value>(name)
        } catch (_: IllegalArgumentException) {
            throw SerializationException("Unknown $serialName value")
        }
    },
)

/**
 * Proof transition: `JsonElement -> JsonObject`.
 *
 * Establishes the exact expected field set. A serializer-local [SerializationException] is the
 * codec signal mapped to closed [WireFailure.InvalidPayload] at the public wire boundary. Raw
 * fields may be extracted only by the matching operation serializer.
 */
internal fun JsonElement.objectWithFields(vararg expected: String): JsonObject {
    val value = try {
        jsonObject
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Expected JSON object")
    }
    if (value.keys != expected.toSet()) {
        throw SerializationException("Unexpected JSON object fields")
    }
    return value
}

/**
 * Proof transition: `JsonElement -> String` at the generated serializer boundary.
 *
 * Establishes a JSON string primitive. A serializer-local [SerializationException] is mapped to
 * closed [WireFailure.InvalidPayload]; raw extraction is permitted only before a stronger
 * protocol value is constructed.
 */
internal fun JsonElement.stringValue(): String {
    val primitive = try {
        jsonPrimitive
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Expected JSON string")
    }
    if (!primitive.isString) throw SerializationException("Expected JSON string")
    return primitive.content
}

/**
 * Proof transition: `JsonObject field -> ProtocolText`.
 *
 * Establishes bounded non-blank text. Invalid JSON or text maps through the serializer to closed
 * [WireFailure.InvalidPayload]. Raw extraction is permitted only here.
 */
internal fun JsonObject.protocolText(name: String): ProtocolText =
    when (val parsed = ProtocolText.parse(getValue(name).stringValue())) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> throw SerializationException("Invalid $name")
    }

/**
 * Proof transition: `JsonObject field -> ProtocolCount`.
 *
 * Establishes a positive bounded count. Invalid JSON or count maps through the serializer to
 * closed [WireFailure.InvalidPayload]. Raw extraction is permitted only here.
 */
internal fun JsonObject.protocolCount(name: String): ProtocolCount {
    val raw = try {
        getValue(name).jsonPrimitive.intOrNull
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw SerializationException("Invalid $name")
    return when (val parsed = ProtocolCount.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> throw SerializationException("Invalid $name")
    }
}

/**
 * Proof transition: `JsonObject field -> BoundedProtocolList<ProtocolText>`.
 *
 * Establishes bounded collection and member invariants. Invalid input maps through the serializer
 * to closed [WireFailure.InvalidPayload]. Raw list extraction is permitted only here.
 */
internal fun JsonObject.protocolTextList(name: String): BoundedProtocolList<ProtocolText> {
    val values = try {
        getValue(name).jsonArray.map { element ->
            when (val parsed = ProtocolText.parse(element.stringValue())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> throw SerializationException("Invalid $name member")
            }
        }
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid $name")
    }
    return when (val admitted = BoundedProtocolList.create(values)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> throw SerializationException("Invalid $name")
    }
}

internal fun ProtocolText.asJson(): JsonPrimitive = JsonPrimitive(value)

internal fun ProtocolCount.asJson(): JsonPrimitive = JsonPrimitive(value)

internal fun BoundedProtocolList<ProtocolText>.asJson(): JsonArray =
    JsonArray(values.map(ProtocolText::asJson))
