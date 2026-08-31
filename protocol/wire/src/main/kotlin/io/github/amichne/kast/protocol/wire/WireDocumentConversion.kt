package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Closed result of refining one generated wire document into its protocol-owned value. */
internal sealed interface WireDocumentConversion<out Value> {
    data class Converted<Value>(
        val value: Value,
    ) : WireDocumentConversion<Value>

    data object Rejected : WireDocumentConversion<Nothing>
}

internal inline fun <Value, Result> WireDocumentConversion<Value>.mapConverted(
    transform: (Value) -> Result,
): WireDocumentConversion<Result> = when (this) {
    is WireDocumentConversion.Converted -> WireDocumentConversion.Converted(transform(value))
    is WireDocumentConversion.Rejected -> this
}

internal inline fun <Value, Result> WireDocumentConversion<Value>.flatMapConverted(
    transform: (Value) -> WireDocumentConversion<Result>,
): WireDocumentConversion<Result> = when (this) {
    is WireDocumentConversion.Converted -> transform(value)
    is WireDocumentConversion.Rejected -> this
}

internal fun <Value> WireDocumentConversion<WireDocumentConversion<Value>>.flattenConverted():
    WireDocumentConversion<Value> = flatMapConverted { it }

internal inline fun <First, Second, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    transform: (First, Second) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    second.mapConverted { admittedSecond -> transform(admittedFirst, admittedSecond) }
}

internal inline fun <First, Second, Third, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    third: WireDocumentConversion<Third>,
    transform: (First, Second, Third) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    combineConverted(second, third) { admittedSecond, admittedThird ->
        transform(admittedFirst, admittedSecond, admittedThird)
    }
}

internal inline fun <First, Second, Third, Fourth, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    third: WireDocumentConversion<Third>,
    fourth: WireDocumentConversion<Fourth>,
    transform: (First, Second, Third, Fourth) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    combineConverted(second, third, fourth) { admittedSecond, admittedThird, admittedFourth ->
        transform(admittedFirst, admittedSecond, admittedThird, admittedFourth)
    }
}

internal inline fun <First, Second, Third, Fourth, Fifth, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    third: WireDocumentConversion<Third>,
    fourth: WireDocumentConversion<Fourth>,
    fifth: WireDocumentConversion<Fifth>,
    transform: (First, Second, Third, Fourth, Fifth) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    combineConverted(second, third, fourth, fifth) { value2, value3, value4, value5 ->
        transform(admittedFirst, value2, value3, value4, value5)
    }
}

internal inline fun <Input, Output> Iterable<Input>.convertEach(
    transform: (Input) -> WireDocumentConversion<Output>,
): WireDocumentConversion<List<Output>> {
    val converted = mutableListOf<Output>()
    for (input in this) {
        when (val conversion = transform(input)) {
            is WireDocumentConversion.Converted -> converted += conversion.value
            is WireDocumentConversion.Rejected -> return conversion
        }
    }
    return WireDocumentConversion.Converted(converted.toList())
}

/**
 * Proof transition: `Refinement<Value, Failure> -> WireDocumentConversion<Value>`.
 *
 * Preserves a successful domain refinement and closes every domain rejection as
 * [WireDocumentConversion.Rejected]. The public wire failure retains the value's semantic role;
 * raw boundary primitives remain owned by the calling generated document mapper.
 */
internal fun <Value, Failure> Refinement<Value, Failure>.toWireDocumentConversion():
    WireDocumentConversion<Value> = when (this) {
    is Refinement.Refined -> WireDocumentConversion.Converted(value)
    is Refinement.Rejected -> WireDocumentConversion.Rejected
}

/** Factory that binds generated kotlinx serializers to domain-owned protocol values. */
internal class GeneratedWireCodecFactory(
    private val json: Json,
) {
    fun <Value, Document> create(
        serializer: KSerializer<Document>,
        toDocument: (Value) -> Document,
        toValue: (Document) -> WireDocumentConversion<Value>,
    ): WireValueCodec<Value> = WireValueCodec(
        encodeValue = { value -> json.encodeToJsonElement(serializer, toDocument(value)) },
        decodeValue = { element -> toValue(json.decodeFromJsonElement(serializer, element)) },
    )

    /** Binds a generated serializer when the contract value is already the wire document. */
    fun <Value> create(serializer: KSerializer<Value>): WireValueCodec<Value> = create(
        serializer = serializer,
        toDocument = { value -> value },
        toValue = { document -> WireDocumentConversion.Converted(document) },
    )
}

/** Typed codec whose JSON structure is owned by one generated kotlinx serializer. */
internal class WireValueCodec<Value> internal constructor(
    private val encodeValue: (Value) -> JsonElement,
    private val decodeValue: (JsonElement) -> WireDocumentConversion<Value>,
) {
    fun encode(value: Value, role: WireValueRole): WireValueEncoding = try {
        WireValueEncoding.Encoded(encodeValue(value))
    } catch (_: SerializationException) {
        WireValueEncoding.Rejected(WireFailure.PayloadEncodingFailed(role))
    }

    /**
     * Proof transition: `JsonElement -> WireDecoding<Value>`.
     *
     * Establishes the exact generated document shape and a successful closed domain conversion.
     * [WireDocumentConversion.Rejected] and generated-decoder [SerializationException] both
     * project to [WireFailure.InvalidPayload]. Raw JSON remains inside this admitted-envelope
     * boundary, so no conversion-detail protocol is discarded.
     */
    fun decode(value: JsonElement, role: WireValueRole): WireDecoding<Value> {
        val conversion = try {
            decodeValue(value)
        } catch (_: SerializationException) {
            return WireDecoding.Rejected(WireFailure.InvalidPayload(role))
        }
        return when (conversion) {
            is WireDocumentConversion.Converted -> WireDecoding.Decoded(conversion.value)
            is WireDocumentConversion.Rejected ->
                WireDecoding.Rejected(WireFailure.InvalidPayload(role))
        }
    }
}

internal sealed interface WireValueEncoding {
    data class Encoded(
        val value: JsonElement,
    ) : WireValueEncoding

    data class Rejected(
        val failure: WireFailure,
    ) : WireValueEncoding
}
