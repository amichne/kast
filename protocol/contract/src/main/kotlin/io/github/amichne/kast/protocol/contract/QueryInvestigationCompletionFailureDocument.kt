@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryInvestigationCompletionFailureDocument {
    @Serializable
    @SerialName("MISSING_ORIGINAL_INVESTIGATION")
    data object MissingOriginal : QueryInvestigationCompletionFailureDocument

    @Serializable
    @SerialName("ORIGINAL_PATH_SELECTION_INCOMPLETE")
    data object SelectionIncomplete : QueryInvestigationCompletionFailureDocument

    @Serializable
    @SerialName("REQUIRED_OBLIGATIONS_UNRESOLVED")
    data class ObligationsUnresolved(val required: QueryImpactRequiredObligationsDocument) :
        QueryInvestigationCompletionFailureDocument
}

enum class QueryImpactRequiredObligationsFailure {
    EMPTY,
    NON_CANONICAL,
}

/** Nonempty canonical required obligations cannot be decoded as an empty or duplicate failure. */
@Serializable(with = QueryImpactRequiredObligationsSerializer::class)
class QueryImpactRequiredObligationsDocument private constructor(val values: List<ImpactRequiredObligationDocument>) {
    companion object {
        fun from(
            raw: List<ImpactRequiredObligationDocument>
        ): Refinement<QueryImpactRequiredObligationsDocument, QueryImpactRequiredObligationsFailure> =
            when {
                raw.isEmpty() -> Refinement.Rejected(QueryImpactRequiredObligationsFailure.EMPTY)
                raw != raw.distinct().sortedBy { it.ordinal } ->
                    Refinement.Rejected(QueryImpactRequiredObligationsFailure.NON_CANONICAL)
                else ->
                    Refinement.Refined(
                        QueryImpactRequiredObligationsDocument(Collections.unmodifiableList(raw.toList()))
                    )
            }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryImpactRequiredObligationsDocument && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

internal object QueryImpactRequiredObligationsSerializer : KSerializer<QueryImpactRequiredObligationsDocument> {
    private val delegate = ListSerializer(ImpactRequiredObligationDocument.serializer())
    override val descriptor =
        annotatedDescriptor(
            delegate.descriptor,
            ProtocolCollectionConstraint(
                minimumItems = 1,
                maximumItems = ImpactRequiredObligationDocument.entries.size,
                uniqueItems = true,
            ),
        )

    override fun serialize(encoder: Encoder, value: QueryImpactRequiredObligationsDocument) =
        delegate.serialize(encoder, value.values)

    override fun deserialize(decoder: Decoder): QueryImpactRequiredObligationsDocument =
        when (val admitted = QueryImpactRequiredObligationsDocument.from(delegate.deserialize(decoder))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                throw SerializationException("Investigation obligations rejected ${admitted.failure}")
        }
}
