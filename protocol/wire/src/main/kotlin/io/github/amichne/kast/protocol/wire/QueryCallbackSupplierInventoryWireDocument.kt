@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierInventoryDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierPartitionDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QueryCallbackSupplierPartitionWireDocument(
    val formal: QueryCallbackParameterIdentityWireDocument,
    @ProtocolCollectionConstraint(maximumItems = 1000) val suppliers: List<QueryCallbackParameterSupplierWireDocument>,
    @ProtocolCollectionConstraint(maximumItems = 1000) val incoming: List<QueryCallbackForwardingWireDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackSupplierInventoryWireDocument {
    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackSupplierInventoryWireDocument

    @Serializable
    @SerialName("EXHAUSTIVE")
    data class Exhaustive(
        val root: QueryCallbackParameterIdentityWireDocument,
        val basis: ImpactSemanticBasisDocument,
        val domain: QueryRelationDomainFingerprint,
        @ProtocolCollectionConstraint(maximumItems = 1000)
        val partitions: List<QueryCallbackSupplierPartitionWireDocument>,
    ) : QueryCallbackSupplierInventoryWireDocument
}

internal fun QueryCallbackSupplierInventoryDocument.supplierInventoryWire():
    QueryCallbackSupplierInventoryWireDocument =
    when (this) {
        is QueryCallbackSupplierInventoryDocument.Unavailable ->
            QueryCallbackSupplierInventoryWireDocument.Unavailable(cause)
        is QueryCallbackSupplierInventoryDocument.Exhaustive ->
            QueryCallbackSupplierInventoryWireDocument.Exhaustive(
                root.callbackWire(),
                basis,
                domain,
                partitions.values.map { partition ->
                    QueryCallbackSupplierPartitionWireDocument(
                        partition.formal.callbackWire(),
                        partition.suppliers.values.map { it.callbackSupplierWire() },
                        partition.incoming.values.map { it.callbackWire() },
                    )
                },
            )
    }

internal fun QueryCallbackSupplierInventoryWireDocument.toContract():
    WireDocumentConversion<QueryCallbackSupplierInventoryDocument> =
    when (this) {
        is QueryCallbackSupplierInventoryWireDocument.Unavailable ->
            WireDocumentConversion.Converted(QueryCallbackSupplierInventoryDocument.Unavailable(cause))
        is QueryCallbackSupplierInventoryWireDocument.Exhaustive ->
            root.toContract().flatMapConverted { root ->
                partitions
                    .convertEach { partition ->
                        combineConverted(
                            partition.formal.toContract(),
                            partition.suppliers
                                .convertEach { it.toContract() }
                                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
                            partition.incoming
                                .convertEach { it.toContract() }
                                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
                        ) { formal, suppliers, incoming ->
                            QueryCallbackSupplierPartitionDocument(formal, suppliers, incoming)
                        }
                    }
                    .flatMapConverted { values -> BoundedProtocolList.create(values).toWireDocumentConversion() }
                    .flatMapConverted {
                        QueryCallbackSupplierInventoryDocument.Exhaustive.create(root, basis, domain, it)
                            .toWireDocumentConversion()
                    }
            }
    }
