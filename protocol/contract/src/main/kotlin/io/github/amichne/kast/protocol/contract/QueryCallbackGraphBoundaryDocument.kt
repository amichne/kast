package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Exact admitted file and nonempty range survive even when result retention is unavailable. */
@Serializable(with = QueryCallbackGraphBoundarySerializer::class)
@ConsistentCopyVisibility
data class QueryCallbackGraphBoundaryDocument
private constructor(val file: ProtocolText, val range: SourceRangeDocument) {
    companion object {
        fun from(file: ProtocolText, range: SourceRangeDocument): QueryCallbackGraphBoundaryDocument =
            QueryCallbackGraphBoundaryDocument(file, range)
    }
}

@Serializable
private data class QueryCallbackGraphBoundaryFields(
    val file: ProtocolText,
    val start: ProtocolOffset,
    val end: ProtocolOffset,
)

internal object QueryCallbackGraphBoundarySerializer : KSerializer<QueryCallbackGraphBoundaryDocument> {
    private val delegate = QueryCallbackGraphBoundaryFields.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryCallbackGraphBoundaryDocument) =
        delegate.serialize(
            encoder,
            QueryCallbackGraphBoundaryFields(value.file, value.range.startInclusive, value.range.endExclusive),
        )

    override fun deserialize(decoder: Decoder): QueryCallbackGraphBoundaryDocument {
        val raw = delegate.deserialize(decoder)
        return when (val range = SourceRangeDocument.create(raw.start, raw.end)) {
            is Refinement.Refined -> QueryCallbackGraphBoundaryDocument.from(raw.file, range.value)
            is Refinement.Rejected -> throw SerializationException("Graph exclusion boundary rejected ${range.failure}")
        }
    }
}
