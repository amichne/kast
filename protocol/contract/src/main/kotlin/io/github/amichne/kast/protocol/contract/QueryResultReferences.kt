package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

enum class QueryResultReferenceFailure {
    MALFORMED
}

/** A retained query result, distinct from an exact symbol and an execution continuation. */
@Serializable(with = QueryResultReferenceSerializer::class)
@JvmInline
value class QueryResultReference private constructor(val value: String) {
    companion object {
        private val syntax = Regex("result:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun parse(raw: String): Refinement<QueryResultReference, QueryResultReferenceFailure> =
            if (syntax.matches(raw)) Refinement.Refined(QueryResultReference(raw))
            else Refinement.Rejected(QueryResultReferenceFailure.MALFORMED)
    }
}

object QueryResultReferenceSerializer : KSerializer<QueryResultReference> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("QueryResultReference", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: QueryResultReference) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): QueryResultReference =
        when (val parsed = QueryResultReference.parse(decoder.decodeString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException("Malformed retained query result reference")
        }
}

enum class QueryResultCursorFailure {
    OUT_OF_RANGE
}

private const val MAX_QUERY_RESULT_CURSOR = 1_000_000

/** Presentation offset within one separately supplied retained-result reference. */
@Serializable(with = QueryResultCursorSerializer::class)
@JvmInline
value class QueryResultCursor private constructor(val value: Int) {
    companion object {
        val Start = QueryResultCursor(0)

        fun parse(raw: Int): Refinement<QueryResultCursor, QueryResultCursorFailure> =
            if (raw in 0..MAX_QUERY_RESULT_CURSOR) Refinement.Refined(QueryResultCursor(raw))
            else Refinement.Rejected(QueryResultCursorFailure.OUT_OF_RANGE)
    }
}

object QueryResultCursorSerializer : KSerializer<QueryResultCursor> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("QueryResultCursor", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: QueryResultCursor) = encoder.encodeInt(value.value)

    override fun deserialize(decoder: Decoder): QueryResultCursor =
        when (val parsed = QueryResultCursor.parse(decoder.decodeInt())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException("Invalid retained query result cursor")
        }
}

@Serializable
@JsonClassDiscriminator("kind")
sealed interface QueryResultRetention {
    @Serializable @SerialName("not_requested") data object NotRequested : QueryResultRetention

    @Serializable
    @SerialName("retained")
    data class Retained(val reference: QueryResultReference) : QueryResultRetention

    @Serializable @SerialName("capacity_exceeded") data object CapacityExceeded : QueryResultRetention
}
