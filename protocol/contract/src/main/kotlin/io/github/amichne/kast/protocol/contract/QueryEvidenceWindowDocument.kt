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

/** A presentation ordinal in retained evidence, independent of row offsets and delivered detail. */
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
    DEFERRED_DETAIL,
}

enum class QueryEvidenceWindowFailure {
    REVERSED,
    OUTSIDE_RESULT,
    INVALID_KIND,
}

/** Presentation positions include deferred summaries. FINAL applies only when this page has no deferred detail. */
@ConsistentCopyVisibility
@Serializable(with = QueryEvidenceWindowSerializer::class)
data class QueryEvidenceWindowDocument
private constructor(
    val start: QueryEvidenceCursor,
    val end: QueryEvidenceCursor,
    val total: QueryEvidenceCursor,
    val deferredCount: Int = 0,
) {
    val type: QueryEvidenceWindowKind
        get() =
            if (end != total) QueryEvidenceWindowKind.MORE
            else if (deferredCount == 0) QueryEvidenceWindowKind.FINAL else QueryEvidenceWindowKind.DEFERRED_DETAIL

    val nextCursor: QueryEvidenceCursor?
        get() = if (type == QueryEvidenceWindowKind.MORE) end else null

    fun prefix(count: Int): Refinement<QueryEvidenceWindowDocument, QueryEvidenceWindowFailure> =
        if (count !in 0..end.value - start.value || !admitsDeferredPrefix(count))
            Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
        else
            when (val selected = QueryEvidenceCursor.parse(start.value + count)) {
                is Refinement.Refined -> create(start, selected.value, total, if (count == 0) 0 else deferredCount)
                is Refinement.Rejected -> Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
            }

    private fun admitsDeferredPrefix(count: Int): Boolean =
        deferredCount == 0 || count == 0 || count == end.value - start.value

    companion object {
        fun create(
            start: QueryEvidenceCursor,
            end: QueryEvidenceCursor,
            total: QueryEvidenceCursor,
            deferredCount: Int = 0,
        ): Refinement<QueryEvidenceWindowDocument, QueryEvidenceWindowFailure> =
            when {
                start.value > end.value -> Refinement.Rejected(QueryEvidenceWindowFailure.REVERSED)
                end.value > total.value -> Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
                deferredCount !in 0..end.value - start.value ->
                    Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT)
                else -> Refinement.Refined(QueryEvidenceWindowDocument(start, end, total, deferredCount))
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
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("deferred_count")
    @ProtocolIntegerConstraint(minimum = 0, maximum = MAX_QUERY_EVIDENCE_CURSOR)
    val deferredCount: Int = 0,
)

internal object QueryEvidenceWindowSerializer : KSerializer<QueryEvidenceWindowDocument> {
    private val delegate = QueryEvidenceWindowBoundary.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryEvidenceWindowDocument) =
        delegate.serialize(
            encoder,
            QueryEvidenceWindowBoundary(value.type, value.start, value.end, value.total, value.deferredCount),
        )

    override fun deserialize(decoder: Decoder): QueryEvidenceWindowDocument {
        val input = delegate.deserialize(decoder)
        val admitted =
            when (
                val window =
                    QueryEvidenceWindowDocument.create(input.start, input.end, input.total, input.deferredCount)
            ) {
                is Refinement.Refined -> window.value
                is Refinement.Rejected ->
                    throw SerializationException("Invalid retained evidence window: ${window.failure}")
            }
        if (input.type != admitted.type)
            throw SerializationException("Invalid retained evidence window: ${QueryEvidenceWindowFailure.INVALID_KIND}")
        return admitted
    }
}
