@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodySupplyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackOwnerExecutionDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Required
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
    @SerialName("DEFAULT_PARAMETER")
    data class DefaultParameter(val parameter: RelationOccurrenceWireDocument) : QueryCallbackBodySupplyWireDocument

    @Serializable
    @SerialName("DIRECT_INVOCATION")
    data class DirectInvocation(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackBodySupplyWireDocument

    @Serializable
    @SerialName("RETURNED")
    data class Returned(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackBodySupplyWireDocument

    @Serializable @SerialName("UNSUPPORTED") data object Unsupported : QueryCallbackBodySupplyWireDocument

    @Serializable @SerialName("STORED") data object Stored : QueryCallbackBodySupplyWireDocument
}

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackOwnerExecutionWireDocument {
    @Serializable @SerialName("UNPROVEN") data object Unproven : QueryCallbackOwnerExecutionWireDocument

    @Serializable
    @SerialName("OBSERVED")
    data class Observed(val flow: QueryCallbackFlowWireDocument.Observed) : QueryCallbackOwnerExecutionWireDocument

    @Serializable
    @SerialName("IMMUTABLE")
    data class Immutable(
        val flow: QueryImmutableCallbackFlowWireDocument,
        val owner: QueryCallbackBodyWireDocument.Named,
    ) : QueryCallbackOwnerExecutionWireDocument
}

@Serializable(with = QueryCallbackBodyBindingWireSerializer::class)
@KeepGeneratedSerializer
internal data class QueryCallbackBodyBindingWireDocument(
    val body: QueryCallbackBodyWireDocument.Anonymous,
    val supply: QueryCallbackBodySupplyWireDocument,
    val binding: QueryCallbackBindingWireDocument,
    val obligations: List<QueryCallbackFlowCauseDocument>,
    @Required
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val execution: QueryCallbackOwnerExecutionWireDocument = QueryCallbackOwnerExecutionWireDocument.Unproven,
)

internal fun QueryCallbackBodyBindingDocument.callbackWire(): QueryCallbackBodyBindingWireDocument =
    QueryCallbackBodyBindingWireDocument(
        body.callbackWire(),
        when (val supplied = supply) {
            is QueryCallbackBodySupplyDocument.Invocation ->
                QueryCallbackBodySupplyWireDocument.Invocation(supplied.occurrence.callbackSupplyWire())
            is QueryCallbackBodySupplyDocument.DefaultParameter ->
                QueryCallbackBodySupplyWireDocument.DefaultParameter(supplied.parameter.callbackSupplyWire())
            is QueryCallbackBodySupplyDocument.DirectInvocation ->
                QueryCallbackBodySupplyWireDocument.DirectInvocation(supplied.occurrence.callbackSupplyWire())
            is QueryCallbackBodySupplyDocument.Returned ->
                QueryCallbackBodySupplyWireDocument.Returned(supplied.occurrence.callbackSupplyWire())
            QueryCallbackBodySupplyDocument.Unsupported -> QueryCallbackBodySupplyWireDocument.Unsupported
            QueryCallbackBodySupplyDocument.Stored -> QueryCallbackBodySupplyWireDocument.Stored
        },
        binding.callbackWire(),
        obligations.values,
        when (val observed = execution) {
            QueryCallbackOwnerExecutionDocument.Unproven -> QueryCallbackOwnerExecutionWireDocument.Unproven
            is QueryCallbackOwnerExecutionDocument.Immutable ->
                QueryCallbackOwnerExecutionWireDocument.Immutable(
                    observed.flow.immutableFlowWire(),
                    QueryCallbackBodyWireDocument.Named(observed.owner.callable.callbackWire()),
                )
            is QueryCallbackOwnerExecutionDocument.Observed ->
                QueryCallbackOwnerExecutionWireDocument.Observed(observed.flow.observedCallbackWire())
        },
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
        is QueryCallbackBodySupplyWireDocument.DefaultParameter ->
            parameter.toContract().mapConverted { QueryCallbackBodySupplyDocument.DefaultParameter(it) }
        is QueryCallbackBodySupplyWireDocument.DirectInvocation ->
            occurrence.toContract().mapConverted { QueryCallbackBodySupplyDocument.DirectInvocation(it) }
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
                execution.toContract().mapConverted { execution ->
                    QueryCallbackBodyBindingDocument(body, supply, binding, obligations, execution)
                }
            }
            .flattenConverted()
    }

private fun QueryCallbackOwnerExecutionWireDocument.toContract():
    WireDocumentConversion<QueryCallbackOwnerExecutionDocument> =
    when (this) {
        QueryCallbackOwnerExecutionWireDocument.Unproven ->
            WireDocumentConversion.Converted(QueryCallbackOwnerExecutionDocument.Unproven)
        is QueryCallbackOwnerExecutionWireDocument.Immutable ->
            combineConverted(flow.toContract(), owner.callable.toContract()) { flow, owner ->
                QueryCallbackOwnerExecutionDocument.Immutable(
                    flow,
                    io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument.Named(owner),
                )
            }
        is QueryCallbackOwnerExecutionWireDocument.Observed ->
            flow.toContract().flatMapConverted { flow ->
                when (flow) {
                    is QueryCallbackFlowDocument.Observed ->
                        WireDocumentConversion.Converted(QueryCallbackOwnerExecutionDocument.Observed(flow))
                    is QueryCallbackFlowDocument.Immutable,
                    is QueryCallbackFlowDocument.Unavailable,
                    is QueryCallbackFlowDocument.ContractRejected -> WireDocumentConversion.Rejected
                }
            }
    }
