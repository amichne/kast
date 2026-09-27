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

enum class QueryBindingNameFailure {
    MALFORMED
}

/** One query-local name, admitted before a binding can be captured or referenced. */
@Serializable(with = QueryBindingNameDocumentSerializer::class)
@JvmInline
value class QueryBindingNameDocument private constructor(val value: String) {
    companion object {
        private val syntax = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

        fun parse(raw: String): Refinement<QueryBindingNameDocument, QueryBindingNameFailure> =
            if (syntax.matches(raw)) Refinement.Refined(QueryBindingNameDocument(raw))
            else Refinement.Rejected(QueryBindingNameFailure.MALFORMED)
    }
}

object QueryBindingNameDocumentSerializer : KSerializer<QueryBindingNameDocument> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("QueryBindingNameDocument", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: QueryBindingNameDocument) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): QueryBindingNameDocument =
        when (val parsed = QueryBindingNameDocument.parse(decoder.decodeString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException("Malformed query binding name")
        }
}

@Serializable
sealed interface QueryJoinModeDocument {
    @Serializable
    @SerialName("inner")
    data class Inner(
        @SerialName("left_name") val leftName: QueryBindingNameDocument,
        @SerialName("right_name") val rightName: QueryBindingNameDocument,
    ) : QueryJoinModeDocument

    @Serializable @SerialName("semi") data object Semi : QueryJoinModeDocument

    @Serializable @SerialName("anti") data object Anti : QueryJoinModeDocument
}

/** Each cell carries its exact symbol identity and established compiler evidence. */
sealed interface QueryBindingCellDocument {
    val name: QueryBindingNameDocument
    val symbol: QueryResultItemDocument.ExactSymbol

    data class Symbol(
        override val name: QueryBindingNameDocument,
        override val symbol: QueryResultItemDocument.ExactSymbol,
    ) : QueryBindingCellDocument

    data class Occurrence(
        override val name: QueryBindingNameDocument,
        override val symbol: QueryResultItemDocument.ExactSymbol,
        val relation: RelationFactDocument,
    ) : QueryBindingCellDocument
}
