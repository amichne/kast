@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCallbackFactoryBodyCallsWireDocument {
    @Serializable @SerialName("NOT_APPLICABLE") data object NotApplicable : QueryCallbackFactoryBodyCallsWireDocument

    @Serializable
    @SerialName("EXHAUSTIVE")
    data class Exhaustive(
        val body: QueryCallbackBodyWireDocument,
        @ProtocolCollectionConstraint(maximumItems = 1000) val calls: List<QueryCallbackFactoryBodyCallWireDocument>,
    ) : QueryCallbackFactoryBodyCallsWireDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCallbackFactoryBodyCallWireDocument {
    @Serializable
    @SerialName("NAMED")
    data class Named(val occurrence: RelationOccurrenceWireDocument, val target: QueryCallbackCallableWireDocument) :
        QueryCallbackFactoryBodyCallWireDocument

    @Serializable
    @SerialName("CAPTURED")
    data class Captured(
        val invocation: QueryCallbackInvocationWireDocument,
        val formal: QueryCallbackParameterIdentityWireDocument,
    ) : QueryCallbackFactoryBodyCallWireDocument

    @Serializable
    @SerialName("BOUNDARY")
    data class Boundary(
        val occurrence: RelationOccurrenceWireDocument,
        val callable: QuerySourceLessCallableWireDocument,
        val disposition: QuerySourceLessCallableDispositionDocument,
    ) : QueryCallbackFactoryBodyCallWireDocument
}

fun QueryCallbackFactoryBodyCallsDocument.bodyCallsWire(): QueryCallbackFactoryBodyCallsWireDocument =
    when (this) {
        QueryCallbackFactoryBodyCallsDocument.NotApplicable -> QueryCallbackFactoryBodyCallsWireDocument.NotApplicable
        is QueryCallbackFactoryBodyCallsDocument.Exhaustive ->
            QueryCallbackFactoryBodyCallsWireDocument.Exhaustive(
                body.callbackWire(),
                calls.values.map { it.bodyCallWire() },
            )
    }

private fun QueryCallbackFactoryBodyCallDocument.bodyCallWire(): QueryCallbackFactoryBodyCallWireDocument =
    when (this) {
        is QueryCallbackFactoryBodyCallDocument.Named ->
            QueryCallbackFactoryBodyCallWireDocument.Named(occurrence.callbackWire(), target.callbackWire())
        is QueryCallbackFactoryBodyCallDocument.Captured ->
            QueryCallbackFactoryBodyCallWireDocument.Captured(invocation.invocationWire(), formal.callbackWire())
        is QueryCallbackFactoryBodyCallDocument.Boundary ->
            QueryCallbackFactoryBodyCallWireDocument.Boundary(
                occurrence.callbackWire(),
                callable.toWireDocument(),
                disposition,
            )
    }

fun QueryCallbackFactoryBodyCallsWireDocument.toContract():
    WireDocumentConversion<QueryCallbackFactoryBodyCallsDocument> =
    when (this) {
        QueryCallbackFactoryBodyCallsWireDocument.NotApplicable ->
            WireDocumentConversion.Converted(QueryCallbackFactoryBodyCallsDocument.NotApplicable)
        is QueryCallbackFactoryBodyCallsWireDocument.Exhaustive ->
            combineConverted(
                    body.toContract(),
                    calls
                        .convertEach { it.toContract() }
                        .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
                ) { body, calls ->
                    if (body is QueryCallbackBodyDocument.Anonymous)
                        QueryCallbackFactoryBodyCallsDocument.Exhaustive.create(body, calls).toWireDocumentConversion()
                    else WireDocumentConversion.Rejected
                }
                .flattenConverted()
    }

private fun QueryCallbackFactoryBodyCallWireDocument.toContract():
    WireDocumentConversion<QueryCallbackFactoryBodyCallDocument> =
    when (this) {
        is QueryCallbackFactoryBodyCallWireDocument.Named ->
            combineConverted(occurrence.toContract(), target.toContract()) { occurrence, target ->
                QueryCallbackFactoryBodyCallDocument.Named(occurrence, target)
            }
        is QueryCallbackFactoryBodyCallWireDocument.Captured ->
            combineConverted(invocation.toContract(), formal.toContract()) { invocation, formal ->
                QueryCallbackFactoryBodyCallDocument.Captured(invocation, formal)
            }
        is QueryCallbackFactoryBodyCallWireDocument.Boundary ->
            combineConverted(occurrence.toContract(), callable.toContract()) { occurrence, callable ->
                QueryCallbackFactoryBodyCallDocument.Boundary(occurrence, callable, disposition)
            }
    }
