@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingEvidenceWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackReferenceReceiverWireDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowWireDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import io.github.amichne.kast.protocol.contract.WireDocumentConversion
import io.github.amichne.kast.protocol.contract.callbackWire
import io.github.amichne.kast.protocol.contract.combineConverted
import io.github.amichne.kast.protocol.contract.convertEach
import io.github.amichne.kast.protocol.contract.flatMapConverted
import io.github.amichne.kast.protocol.contract.immutableFlowWire
import io.github.amichne.kast.protocol.contract.mapConverted
import io.github.amichne.kast.protocol.contract.toContract
import io.github.amichne.kast.protocol.contract.toWireDocumentConversion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

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
