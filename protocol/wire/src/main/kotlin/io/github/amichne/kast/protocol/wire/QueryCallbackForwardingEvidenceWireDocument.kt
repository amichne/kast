@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingEvidenceDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackForwardingEvidenceWireDocument {
    @Serializable
    @SerialName("INVOCATION_ROUTES")
    data object InvocationRoutes : QueryCallbackForwardingEvidenceWireDocument

    @Serializable
    @SerialName("EXHAUSTED_GRAPH")
    data class ExhaustedGraph(
        val root: QueryCallbackParameterIdentityWireDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000, uniqueItems = true)
        val formals: List<QueryCallbackParameterIdentityWireDocument>,
        @ProtocolCollectionConstraint(maximumItems = 1000, uniqueItems = true)
        val forwardings: List<QueryCallbackForwardingWireDocument>,
    ) : QueryCallbackForwardingEvidenceWireDocument
}

internal fun QueryCallbackForwardingEvidenceDocument.callbackWire(): QueryCallbackForwardingEvidenceWireDocument =
    when (this) {
        QueryCallbackForwardingEvidenceDocument.InvocationRoutes ->
            QueryCallbackForwardingEvidenceWireDocument.InvocationRoutes
        is QueryCallbackForwardingEvidenceDocument.ExhaustedGraph ->
            QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph(
                root.callbackWire(),
                formals.values.map { it.callbackWire() },
                forwardings.values.map { it.callbackWire() },
            )
    }

internal fun QueryCallbackForwardingEvidenceWireDocument.toContract():
    WireDocumentConversion<QueryCallbackForwardingEvidenceDocument> =
    when (this) {
        QueryCallbackForwardingEvidenceWireDocument.InvocationRoutes ->
            WireDocumentConversion.Converted(QueryCallbackForwardingEvidenceDocument.InvocationRoutes)
        is QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph ->
            root.toContract().flatMapConverted { root ->
                formals
                    .convertEach { it.toContract() }
                    .flatMapConverted { formals ->
                        forwardings
                            .convertEach { it.toContract() }
                            .flatMapConverted { forwardings ->
                                combineConverted(
                                    BoundedProtocolList.create(formals).toWireDocumentConversion(),
                                    BoundedProtocolList.create(forwardings).toWireDocumentConversion(),
                                ) { formals, forwardings ->
                                    QueryCallbackForwardingEvidenceDocument.ExhaustedGraph(root, formals, forwardings)
                                }
                            }
                    }
            }
    }
