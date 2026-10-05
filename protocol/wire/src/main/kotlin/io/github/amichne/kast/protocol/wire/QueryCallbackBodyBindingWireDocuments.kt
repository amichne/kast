@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodySupplyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackBodySupplyWireDocument {
    @Serializable
    @SerialName("INVOCATION")
    data class Invocation(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackBodySupplyWireDocument

    @Serializable
    @SerialName("RETURNED")
    data class Returned(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackBodySupplyWireDocument

    @Serializable @SerialName("UNSUPPORTED") data object Unsupported : QueryCallbackBodySupplyWireDocument

    @Serializable @SerialName("STORED") data object Stored : QueryCallbackBodySupplyWireDocument
}

@Serializable
internal data class QueryCallbackBodyBindingWireDocument(
    val body: QueryCallbackBodyWireDocument.Anonymous,
    val supply: QueryCallbackBodySupplyWireDocument,
    val binding: QueryCallbackBindingWireDocument,
    val obligations: List<QueryCallbackFlowCauseDocument>,
)

internal fun QueryCallbackBodyBindingDocument.callbackWire() =
    QueryCallbackBodyBindingWireDocument(
        body.callbackWire(),
        when (val supplied = supply) {
            is QueryCallbackBodySupplyDocument.Invocation ->
                QueryCallbackBodySupplyWireDocument.Invocation(supplied.occurrence.callbackSupplyWire())
            is QueryCallbackBodySupplyDocument.Returned ->
                QueryCallbackBodySupplyWireDocument.Returned(supplied.occurrence.callbackSupplyWire())
            QueryCallbackBodySupplyDocument.Unsupported -> QueryCallbackBodySupplyWireDocument.Unsupported
            QueryCallbackBodySupplyDocument.Stored -> QueryCallbackBodySupplyWireDocument.Stored
        },
        binding.callbackWire(),
        obligations.values,
    )

private fun RelationOccurrenceDocument.callbackSupplyWire() =
    RelationOccurrenceWireDocument(
        candidateSelector.value,
        file.value,
        range.toWireDocument(),
    )

private fun QueryCallbackBodySupplyWireDocument.toContract(): WireDocumentConversion<QueryCallbackBodySupplyDocument> =
    when (this) {
        is QueryCallbackBodySupplyWireDocument.Invocation ->
            occurrence.toContract().mapConverted {
                QueryCallbackBodySupplyDocument.Invocation(it)
            }
        is QueryCallbackBodySupplyWireDocument.Returned ->
            occurrence.toContract().mapConverted { QueryCallbackBodySupplyDocument.Returned(it) }
        QueryCallbackBodySupplyWireDocument.Unsupported ->
            WireDocumentConversion.Converted(QueryCallbackBodySupplyDocument.Unsupported)
        QueryCallbackBodySupplyWireDocument.Stored ->
            WireDocumentConversion.Converted(QueryCallbackBodySupplyDocument.Stored)
    }

internal fun QueryCallbackBodyBindingWireDocument.toContract():
    WireDocumentConversion<QueryCallbackBodyBindingDocument> =
    body.toContract().flatMapConverted { body ->
        combineConverted(
            supply.toContract(),
            binding.toContract(),
            BoundedProtocolList.create(obligations).toWireDocumentConversion(),
        ) { supply, binding, obligations ->
            QueryCallbackBodyBindingDocument(body, supply, binding, obligations)
        }
    }
