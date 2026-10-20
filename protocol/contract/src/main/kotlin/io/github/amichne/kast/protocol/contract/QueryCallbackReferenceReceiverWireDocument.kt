@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCallbackReferenceReceiverWireDocument {
    @Serializable @SerialName("ABSENT") data object Absent : QueryCallbackReferenceReceiverWireDocument

    @Serializable @SerialName("UNBOUND") data object Unbound : QueryCallbackReferenceReceiverWireDocument

    @Serializable
    @SerialName("BOUND")
    data class Bound(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackReferenceReceiverWireDocument

    @Serializable
    @SerialName("IMPLICIT")
    data class Implicit(val declaration: QueryCallbackCallableWireDocument) : QueryCallbackReferenceReceiverWireDocument
}

fun QueryCallbackReferenceReceiverDocument.callbackWire(): QueryCallbackReferenceReceiverWireDocument =
    when (this) {
        QueryCallbackReferenceReceiverDocument.Absent -> QueryCallbackReferenceReceiverWireDocument.Absent
        QueryCallbackReferenceReceiverDocument.Unbound -> QueryCallbackReferenceReceiverWireDocument.Unbound
        is QueryCallbackReferenceReceiverDocument.Bound ->
            QueryCallbackReferenceReceiverWireDocument.Bound(occurrence.callbackWire())
        is QueryCallbackReferenceReceiverDocument.Implicit ->
            QueryCallbackReferenceReceiverWireDocument.Implicit(declaration.callbackWire())
    }

fun QueryCallbackReferenceReceiverWireDocument.toContract():
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
