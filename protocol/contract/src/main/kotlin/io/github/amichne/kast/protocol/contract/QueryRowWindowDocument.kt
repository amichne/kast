package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class QueryRowWindowKind {
    MORE,
    FINAL,
}

/** Public row bounds projected from a retained window, independently of retained evidence progress. */
@ConsistentCopyVisibility
@Serializable(with = QueryRowWindowSerializer::class)
data class QueryRowWindowDocument
private constructor(
    val start: QueryResultCursor,
    val end: QueryResultCursor,
    val total: QueryResultCursor,
) {
    val type: QueryRowWindowKind
        get() = if (end == total) QueryRowWindowKind.FINAL else QueryRowWindowKind.MORE

    companion object {
        fun from(window: QueryRetainedPresentationWindow): QueryRowWindowDocument =
            QueryRowWindowDocument(window.start, window.end, window.resultEnd)

        internal fun decode(input: QueryRowWindowBoundary): QueryRowWindowDocument {
            if (input.start.value > input.end.value || input.end.value > input.total.value)
                throw SerializationException("Invalid retained row window bounds")
            val window = QueryRowWindowDocument(input.start, input.end, input.total)
            if (input.type != window.type) throw SerializationException("Invalid retained row window kind")
            return window
        }
    }
}

@Serializable
@SerialName("io.github.amichne.kast.protocol.contract.QueryRowWindowDocument")
internal data class QueryRowWindowBoundary(
    val type: QueryRowWindowKind,
    @ProtocolIntegerConstraint(minimum = 0, maximum = 1_000_000) val start: QueryResultCursor,
    @ProtocolIntegerConstraint(minimum = 0, maximum = 1_000_000) val end: QueryResultCursor,
    @ProtocolIntegerConstraint(minimum = 0, maximum = 1_000_000) val total: QueryResultCursor,
)

internal object QueryRowWindowSerializer : KSerializer<QueryRowWindowDocument> {
    private val delegate = QueryRowWindowBoundary.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryRowWindowDocument) =
        delegate.serialize(encoder, QueryRowWindowBoundary(value.type, value.start, value.end, value.total))

    override fun deserialize(decoder: Decoder): QueryRowWindowDocument =
        QueryRowWindowDocument.decode(delegate.deserialize(decoder))
}
