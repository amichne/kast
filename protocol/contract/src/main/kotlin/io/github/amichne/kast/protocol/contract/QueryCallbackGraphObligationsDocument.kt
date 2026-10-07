package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

enum class QueryCallbackGraphObligationsFailure {
    EMPTY,
    NON_CANONICAL,
}

@Serializable(with = QueryCallbackGraphObligationsSerializer::class)
class QueryCallbackGraphObligationsDocument private constructor(val values: List<QueryCallbackFlowCauseDocument>) {
    companion object {
        fun from(
            raw: List<QueryCallbackFlowCauseDocument>
        ): io.github.amichne.kast.kernel.Refinement<
            QueryCallbackGraphObligationsDocument,
            QueryCallbackGraphObligationsFailure,
        > =
            when {
                raw.isEmpty() ->
                    io.github.amichne.kast.kernel.Refinement.Rejected(QueryCallbackGraphObligationsFailure.EMPTY)
                raw != raw.distinct().sortedBy { it.ordinal } ->
                    io.github.amichne.kast.kernel.Refinement.Rejected(
                        QueryCallbackGraphObligationsFailure.NON_CANONICAL
                    )
                else ->
                    io.github.amichne.kast.kernel.Refinement.Refined(
                        QueryCallbackGraphObligationsDocument(java.util.Collections.unmodifiableList(raw.toList()))
                    )
            }
    }

    override fun equals(other: Any?): Boolean = other is QueryCallbackGraphObligationsDocument && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

internal object QueryCallbackGraphObligationsSerializer :
    kotlinx.serialization.KSerializer<QueryCallbackGraphObligationsDocument> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(QueryCallbackFlowCauseDocument.serializer())
    override val descriptor =
        annotatedDescriptor(
            delegate.descriptor,
            ProtocolCollectionConstraint(
                minimumItems = 1,
                maximumItems = QueryCallbackFlowCauseDocument.entries.size,
                uniqueItems = true,
            ),
        )

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: QueryCallbackGraphObligationsDocument,
    ) = delegate.serialize(encoder, value.values)

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): QueryCallbackGraphObligationsDocument =
        when (val parsed = QueryCallbackGraphObligationsDocument.from(delegate.deserialize(decoder))) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> parsed.value
            is io.github.amichne.kast.kernel.Refinement.Rejected ->
                throw kotlinx.serialization.SerializationException(
                    "Callback graph obligations rejected ${parsed.failure}"
                )
        }
}
