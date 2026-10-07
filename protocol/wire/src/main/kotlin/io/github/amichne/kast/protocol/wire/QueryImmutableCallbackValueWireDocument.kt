@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryImmutableCallbackValueOriginWireDocument {
    @Serializable
    @SerialName("RETURNED")
    data class Returned(
        @SerialName("factory_index")
        @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 999)
        val factoryIndex: Int
    ) : QueryImmutableCallbackValueOriginWireDocument

    @Serializable
    @SerialName("ANONYMOUS")
    data class Anonymous(val body: QueryCallbackBodyWireDocument) : QueryImmutableCallbackValueOriginWireDocument

    @Serializable
    @SerialName("NAMED")
    data class Named(
        val basis: io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument,
        val occurrence: RelationOccurrenceWireDocument,
        val target: QueryCallbackCallableWireDocument,
        @SerialName("dispatch_receiver") val dispatchReceiver: QueryCallbackReferenceReceiverWireDocument,
        @SerialName("extension_receiver") val extensionReceiver: QueryCallbackReferenceReceiverWireDocument,
    ) : QueryImmutableCallbackValueOriginWireDocument
}

@Serializable
internal data class QueryImmutableCallbackValueWireDocument(
    val origin: QueryImmutableCallbackValueOriginWireDocument,
    val source: ImpactValueSiteReferenceDocument,
    val destination: ImpactValueSiteReferenceDocument,
    @ProtocolCollectionConstraint(maximumItems = 1000) val transfers: List<ImpactCompilerTransferDocument>,
    @SerialName("invoked_callables")
    @ProtocolCollectionConstraint(maximumItems = 1000)
    val invokedCallables: List<QueryCallbackCallableWireDocument>,
    @ProtocolCollectionConstraint(maximumItems = 1000) val factories: List<QueryCallbackFactoryWireDocument>,
)

@Serializable
internal data class QueryImmutableCallbackValueNodeWireDocument(
    val origin: QueryImmutableCallbackValueOriginWireDocument,
    val source: ImpactValueSiteReferenceDocument,
    val destination: ImpactValueSiteReferenceDocument,
    @ProtocolCollectionConstraint(maximumItems = 1000) val transfers: List<ImpactCompilerTransferDocument>,
    @SerialName("invoked_callables")
    @ProtocolCollectionConstraint(maximumItems = 1000)
    val invokedCallables: List<QueryCallbackCallableWireDocument>,
)

internal fun QueryImmutableCallbackValueWireDocument.node() =
    QueryImmutableCallbackValueNodeWireDocument(origin, source, destination, transfers, invokedCallables)
