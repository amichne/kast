@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryImmutableCallbackUseWireDocument {
    @Serializable
    @SerialName("SUPPLIED")
    data class Supplied(
        val supplier: QueryCallbackParameterSupplierWireDocument,
        @ProtocolCollectionConstraint(maximumItems = 1000) val invocations: List<QueryCallbackInvocationWireDocument>,
        val forwarding: QueryCallbackForwardingEvidenceWireDocument,
    ) : QueryImmutableCallbackUseWireDocument

    @Serializable
    @SerialName("DIRECT")
    data class Direct(
        val value: QueryImmutableCallbackValueWireDocument,
        val binding: QueryCallbackBindingWireDocument,
    ) : QueryImmutableCallbackUseWireDocument

    @Serializable
    @SerialName("UNUSED")
    data class Unused(val value: QueryImmutableCallbackValueWireDocument) : QueryImmutableCallbackUseWireDocument
}

@Serializable
internal data class QueryImmutableCallbackFlowWireDocument(
    @SerialName("source_value") val sourceValue: QueryImmutableCallbackValueWireDocument,
    @ProtocolCollectionConstraint(maximumItems = 1000) val uses: List<QueryImmutableCallbackUseWireDocument>,
    @ProtocolCollectionConstraint(maximumItems = 1000) val obligations: List<QueryCallbackFlowCauseDocument>,
    val scan: QueryCallbackInvocationScanDocument,
)

internal fun QueryImmutableCallbackFlowDocument.immutableFlowWire() =
    QueryImmutableCallbackFlowWireDocument(
        sourceValue.immutableCallbackWire(),
        uses.values.map { use ->
            when (use) {
                is QueryImmutableCallbackUseDocument.Unused ->
                    QueryImmutableCallbackUseWireDocument.Unused(use.value.immutableCallbackWire())
                is QueryImmutableCallbackUseDocument.Direct ->
                    QueryImmutableCallbackUseWireDocument.Direct(
                        use.value.immutableCallbackWire(),
                        use.binding.callbackWire(),
                    )
                is QueryImmutableCallbackUseDocument.Supplied ->
                    QueryImmutableCallbackUseWireDocument.Supplied(
                        use.supplier.callbackSupplierWire(),
                        use.invocations.values.map {
                            QueryCallbackInvocationWireDocument(
                                it.occurrence.callbackWire(),
                                it.owner.callbackWire(),
                                it.callableTransfers.values,
                                it.forwardings.values.map { edge -> edge.callbackWire() },
                            )
                        },
                        use.forwarding.callbackWire(),
                    )
            }
        },
        obligations.values,
        scan,
    )

internal fun QueryImmutableCallbackFlowWireDocument.toContract():
    WireDocumentConversion<QueryImmutableCallbackFlowDocument> =
    combineConverted(
            sourceValue.toContract(),
            uses
                .convertEach { it.toContract() }
                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
            BoundedProtocolList.create(obligations).toWireDocumentConversion(),
        ) { source, uses, causes ->
            QueryImmutableCallbackFlowDocument.create(source, uses, causes, scan).toWireDocumentConversion()
        }
        .flattenConverted()

internal fun QueryImmutableCallbackUseWireDocument.toContract():
    WireDocumentConversion<QueryImmutableCallbackUseDocument> =
    when (this) {
        is QueryImmutableCallbackUseWireDocument.Unused ->
            value.toContract().mapConverted { QueryImmutableCallbackUseDocument.Unused(it) }
        is QueryImmutableCallbackUseWireDocument.Direct ->
            combineConverted(value.toContract(), binding.toContract()) { value, binding ->
                    if (binding is QueryCallbackBindingDocument.Direct)
                        WireDocumentConversion.Converted(QueryImmutableCallbackUseDocument.Direct(value, binding))
                    else WireDocumentConversion.Rejected
                }
                .flattenConverted()
        is QueryImmutableCallbackUseWireDocument.Supplied ->
            combineConverted(
                supplier.toContract(),
                invocations
                    .convertEach { it.toContract() }
                    .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
                forwarding.toContract(),
            ) { supplier, invocations, forwarding ->
                QueryImmutableCallbackUseDocument.Supplied(supplier, invocations, forwarding)
            }
    }

internal fun QueryImmutableCallbackUseDocument.Supplied.suppliedWire() =
    QueryImmutableCallbackUseWireDocument.Supplied(
        supplier.callbackSupplierWire(),
        invocations.values.map {
            QueryCallbackInvocationWireDocument(
                it.occurrence.callbackWire(),
                it.owner.callbackWire(),
                it.callableTransfers.values,
                it.forwardings.values.map { edge -> edge.callbackWire() },
            )
        },
        forwarding.callbackWire(),
    )
