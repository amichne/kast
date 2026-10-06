package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class QueryEvidenceCursorFailure {
    OUT_OF_RANGE
}

private const val MAX_QUERY_EVIDENCE_CURSOR = 1_000_000L

/** An ordinal in the retained evidence sequence, independent of symbol row offsets. */
@Serializable(with = QueryEvidenceCursorSerializer::class)
@JvmInline
value class QueryEvidenceCursor private constructor(val value: Int) {
    companion object {
        val Start = QueryEvidenceCursor(0)

        fun parse(raw: Int): Refinement<QueryEvidenceCursor, QueryEvidenceCursorFailure> =
            if (raw.toLong() in 0..MAX_QUERY_EVIDENCE_CURSOR) Refinement.Refined(QueryEvidenceCursor(raw))
            else Refinement.Rejected(QueryEvidenceCursorFailure.OUT_OF_RANGE)
    }
}

object QueryEvidenceCursorSerializer : KSerializer<QueryEvidenceCursor> {
    override val descriptor = PrimitiveSerialDescriptor("QueryEvidenceCursor", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: QueryEvidenceCursor) = encoder.encodeInt(value.value)

    override fun deserialize(decoder: Decoder): QueryEvidenceCursor =
        when (val parsed = QueryEvidenceCursor.parse(decoder.decodeInt())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException("Invalid retained evidence cursor")
        }
}

@Serializable
enum class QueryEvidenceWindowKind {
    MORE,
    FINAL,
}

enum class QueryEvidenceWindowFailure {
    REVERSED,
    OUTSIDE_RESULT,
    INVALID_KIND,
}

/** Exact emitted evidence positions; FINAL says only that this evidence page reaches the retained end. */
@ConsistentCopyVisibility
@Serializable(with = QueryEvidenceWindowSerializer::class)
data class QueryEvidenceWindowDocument
private constructor(
    val start: QueryEvidenceCursor,
    val end: QueryEvidenceCursor,
    val total: QueryEvidenceCursor,
) {
    val type: QueryEvidenceWindowKind
        get() = if (end == total) QueryEvidenceWindowKind.FINAL else QueryEvidenceWindowKind.MORE

    val nextCursor: QueryEvidenceCursor?
        get() = if (type == QueryEvidenceWindowKind.MORE) end else null

    fun prefix(count: Int): Refinement<QueryEvidenceWindowDocument, QueryEvidenceWindowFailure> =
        if (count !in 0..end.value - start.value) Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
        else
            when (val selected = QueryEvidenceCursor.parse(start.value + count)) {
                is Refinement.Refined -> create(start, selected.value, total)
                is Refinement.Rejected -> Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
            }

    companion object {
        fun create(
            start: QueryEvidenceCursor,
            end: QueryEvidenceCursor,
            total: QueryEvidenceCursor,
        ): Refinement<QueryEvidenceWindowDocument, QueryEvidenceWindowFailure> =
            when {
                start.value > end.value -> Refinement.Rejected(QueryEvidenceWindowFailure.REVERSED)
                end.value > total.value -> Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
                else -> Refinement.Refined(QueryEvidenceWindowDocument(start, end, total))
            }
    }
}

@Serializable
@SerialName("io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument")
internal data class QueryEvidenceWindowBoundary(
    val type: QueryEvidenceWindowKind,
    @ProtocolIntegerConstraint(minimum = 0, maximum = MAX_QUERY_EVIDENCE_CURSOR) val start: QueryEvidenceCursor,
    @ProtocolIntegerConstraint(minimum = 0, maximum = MAX_QUERY_EVIDENCE_CURSOR) val end: QueryEvidenceCursor,
    @ProtocolIntegerConstraint(minimum = 0, maximum = MAX_QUERY_EVIDENCE_CURSOR) val total: QueryEvidenceCursor,
)

internal object QueryEvidenceWindowSerializer : KSerializer<QueryEvidenceWindowDocument> {
    private val delegate = QueryEvidenceWindowBoundary.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryEvidenceWindowDocument) =
        delegate.serialize(encoder, QueryEvidenceWindowBoundary(value.type, value.start, value.end, value.total))

    override fun deserialize(decoder: Decoder): QueryEvidenceWindowDocument {
        val input = delegate.deserialize(decoder)
        val admitted =
            when (val window = QueryEvidenceWindowDocument.create(input.start, input.end, input.total)) {
                is Refinement.Refined -> window.value
                is Refinement.Rejected ->
                    throw SerializationException("Invalid retained evidence window: ${window.failure}")
            }
        if (input.type != admitted.type)
            throw SerializationException("Invalid retained evidence window: ${QueryEvidenceWindowFailure.INVALID_KIND}")
        return admitted
    }
}
