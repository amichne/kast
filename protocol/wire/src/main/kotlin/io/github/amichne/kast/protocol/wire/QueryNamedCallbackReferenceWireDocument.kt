@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackReferenceReceiverDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackReferenceReceiverWireDocument {
    @Serializable @SerialName("ABSENT") data object Absent : QueryCallbackReferenceReceiverWireDocument

    @Serializable @SerialName("UNBOUND") data object Unbound : QueryCallbackReferenceReceiverWireDocument

    @Serializable
    @SerialName("BOUND")
    data class Bound(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackReferenceReceiverWireDocument

    @Serializable
    @SerialName("IMPLICIT")
    data class Implicit(val declaration: QueryCallbackCallableWireDocument) : QueryCallbackReferenceReceiverWireDocument
}

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryNamedCallbackReferenceFlowWireDocument {
    @Serializable
    @SerialName("IMMUTABLE")
    data class Immutable(val flow: QueryImmutableCallbackFlowWireDocument) : QueryNamedCallbackReferenceFlowWireDocument

    @Serializable
    @SerialName("SUPPLIED")
    data class Supplied(
        val binding: QueryCallbackBindingWireDocument,
        @ProtocolCollectionConstraint(maximumItems = 1000) val invocations: List<QueryCallbackInvocationWireDocument>,
        val forwarding: QueryCallbackForwardingEvidenceWireDocument,
    ) : QueryNamedCallbackReferenceFlowWireDocument

    @Serializable
    @SerialName("DIRECT")
    data class Direct(val binding: QueryCallbackBindingWireDocument) : QueryNamedCallbackReferenceFlowWireDocument

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(
        val obligations: io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument
    ) : QueryNamedCallbackReferenceFlowWireDocument
}

@Serializable
internal data class QueryNamedCallbackReferenceWireDocument(
    val target: QueryCallbackCallableWireDocument,
    @SerialName("dispatch_receiver") val dispatchReceiver: QueryCallbackReferenceReceiverWireDocument,
    @SerialName("extension_receiver") val extensionReceiver: QueryCallbackReferenceReceiverWireDocument,
    val flow: QueryNamedCallbackReferenceFlowWireDocument,
)

internal fun QueryNamedCallbackReferenceDocument.callbackWire() =
    QueryNamedCallbackReferenceWireDocument(
        target.callbackWire(),
        dispatchReceiver.callbackWire(),
        extensionReceiver.callbackWire(),
        when (val proof = flow) {
            is QueryNamedCallbackReferenceFlowDocument.Immutable ->
                QueryNamedCallbackReferenceFlowWireDocument.Immutable(proof.flow.immutableFlowWire())
            is QueryNamedCallbackReferenceFlowDocument.Unavailable ->
                QueryNamedCallbackReferenceFlowWireDocument.Unavailable(proof.obligations)
            is QueryNamedCallbackReferenceFlowDocument.Direct ->
                QueryNamedCallbackReferenceFlowWireDocument.Direct(proof.binding.callbackWire())
            is QueryNamedCallbackReferenceFlowDocument.Supplied ->
                QueryNamedCallbackReferenceFlowWireDocument.Supplied(
                    proof.binding.callbackWire(),
                    proof.invocations.values.map {
                        QueryCallbackInvocationWireDocument(
                            it.occurrence.callbackWire(),
                            it.owner.callbackWire(),
                            it.callableTransfers.values,
                            it.forwardings.values.map { edge -> edge.callbackWire() },
                        )
                    },
                    proof.forwarding.callbackWire(),
                )
        },
    )

internal fun QueryCallbackReferenceReceiverDocument.callbackWire(): QueryCallbackReferenceReceiverWireDocument =
    when (this) {
        QueryCallbackReferenceReceiverDocument.Absent -> QueryCallbackReferenceReceiverWireDocument.Absent
        QueryCallbackReferenceReceiverDocument.Unbound -> QueryCallbackReferenceReceiverWireDocument.Unbound
        is QueryCallbackReferenceReceiverDocument.Bound ->
            QueryCallbackReferenceReceiverWireDocument.Bound(occurrence.callbackWire())
        is QueryCallbackReferenceReceiverDocument.Implicit ->
            QueryCallbackReferenceReceiverWireDocument.Implicit(declaration.callbackWire())
    }

internal fun QueryCallbackReferenceReceiverWireDocument.toContract():
    WireDocumentConversion<QueryCallbackReferenceReceiverDocument> =
    when (this) {
        QueryCallbackReferenceReceiverWireDocument.Absent ->
            WireDocumentConversion.Converted(QueryCallbackReferenceReceiverDocument.Absent)
        QueryCallbackReferenceReceiverWireDocument.Unbound ->
            WireDocumentConversion.Converted(QueryCallbackReferenceReceiverDocument.Unbound)
        is QueryCallbackReferenceReceiverWireDocument.Bound ->
            occurrence.toContract().mapConverted { QueryCallbackReferenceReceiverDocument.Bound(it) }
        is QueryCallbackReferenceReceiverWireDocument.Implicit ->
            declaration.toContract().mapConverted { QueryCallbackReferenceReceiverDocument.Implicit(it) }
    }

private fun QueryNamedCallbackReferenceFlowWireDocument.toContract():
    WireDocumentConversion<QueryNamedCallbackReferenceFlowDocument> =
    when (this) {
        is QueryNamedCallbackReferenceFlowWireDocument.Immutable ->
            flow.toContract().mapConverted { QueryNamedCallbackReferenceFlowDocument.Immutable(it) }
        is QueryNamedCallbackReferenceFlowWireDocument.Unavailable ->
            WireDocumentConversion.Converted(QueryNamedCallbackReferenceFlowDocument.Unavailable(obligations))
        is QueryNamedCallbackReferenceFlowWireDocument.Direct ->
            binding.toContract().flatMapConverted {
                if (it is QueryCallbackBindingDocument.Direct)
                    WireDocumentConversion.Converted(QueryNamedCallbackReferenceFlowDocument.Direct(it))
                else WireDocumentConversion.Rejected
            }
        is QueryNamedCallbackReferenceFlowWireDocument.Supplied ->
            binding.toContract().flatMapConverted { bound ->
                if (bound !is QueryCallbackBindingDocument.Bound) WireDocumentConversion.Rejected
                else
                    invocations
                        .convertEach { it.toContract() }
                        .flatMapConverted { calls ->
                            combineConverted(
                                BoundedProtocolList.create(calls).toWireDocumentConversion(),
                                forwarding.toContract(),
                            ) { values, graph ->
                                QueryNamedCallbackReferenceFlowDocument.Supplied(bound, values, graph)
                            }
                        }
            }
    }

internal fun QueryNamedCallbackReferenceWireDocument.toContract():
    WireDocumentConversion<QueryNamedCallbackReferenceDocument> =
    combineConverted(
        target.toContract(),
        dispatchReceiver.toContract(),
        extensionReceiver.toContract(),
        flow.toContract(),
    ) { target, dispatch, extension, flow ->
        QueryNamedCallbackReferenceDocument(target, dispatch, extension, flow)
    }
