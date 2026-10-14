@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackAccessorKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredDirectInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredSupplierExecutionDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryDeferredSupplierExecutionWireDocument {
    @Serializable @SerialName("DECLARATION") data object Declaration : QueryDeferredSupplierExecutionWireDocument

    @Serializable
    @SerialName("ACCESSOR")
    data class Accessor(val kind: QueryCallbackAccessorKindDocument, val occurrence: RelationOccurrenceWireDocument) :
        QueryDeferredSupplierExecutionWireDocument

    @Serializable
    @SerialName("INITIALIZER")
    data class Initializer(val occurrence: RelationOccurrenceWireDocument) : QueryDeferredSupplierExecutionWireDocument

    @Serializable
    @SerialName("INVOKED")
    data class Invoked(
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000)
        val steps: List<QueryDeferredDirectInvocationWireDocument>
    ) : QueryDeferredSupplierExecutionWireDocument
}

@Serializable
internal data class QueryDeferredDirectInvocationWireDocument(
    val basis: ImpactSemanticBasisDocument,
    val occurrence: RelationOccurrenceWireDocument,
    val owner: QueryCallbackBodyWireDocument,
    val body: QueryCallbackBodyWireDocument.Anonymous,
)

internal fun QueryDeferredSupplierExecutionDocument.callbackWire(): QueryDeferredSupplierExecutionWireDocument =
    when (this) {
        QueryDeferredSupplierExecutionDocument.Declaration -> QueryDeferredSupplierExecutionWireDocument.Declaration
        is QueryDeferredSupplierExecutionDocument.Accessor ->
            QueryDeferredSupplierExecutionWireDocument.Accessor(kind, occurrence.callbackWire())
        is QueryDeferredSupplierExecutionDocument.Initializer ->
            QueryDeferredSupplierExecutionWireDocument.Initializer(occurrence.callbackWire())
        is QueryDeferredSupplierExecutionDocument.Invoked ->
            QueryDeferredSupplierExecutionWireDocument.Invoked(
                steps.values.map {
                    QueryDeferredDirectInvocationWireDocument(
                        it.binding.basis,
                        it.binding.occurrence.callbackWire(),
                        it.binding.owner.callbackWire(),
                        it.body.callbackWire(),
                    )
                }
            )
    }

internal fun QueryDeferredSupplierExecutionWireDocument.toContract():
    WireDocumentConversion<QueryDeferredSupplierExecutionDocument> =
    when (this) {
        QueryDeferredSupplierExecutionWireDocument.Declaration ->
            WireDocumentConversion.Converted(QueryDeferredSupplierExecutionDocument.Declaration)
        is QueryDeferredSupplierExecutionWireDocument.Accessor ->
            occurrence.toContract().mapConverted { QueryDeferredSupplierExecutionDocument.Accessor(kind, it) }
        is QueryDeferredSupplierExecutionWireDocument.Initializer ->
            occurrence.toContract().mapConverted { QueryDeferredSupplierExecutionDocument.Initializer(it) }
        is QueryDeferredSupplierExecutionWireDocument.Invoked ->
            steps
                .convertEach { step ->
                    combineConverted(step.occurrence.toContract(), step.owner.toContract(), step.body.toContract()) {
                        occurrence,
                        owner,
                        body ->
                        QueryDeferredDirectInvocationDocument(
                            QueryCallbackBindingDocument.Direct(step.basis, occurrence, owner),
                            body,
                        )
                    }
                }
                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() }
                .mapConverted { QueryDeferredSupplierExecutionDocument.Invoked(it) }
    }
