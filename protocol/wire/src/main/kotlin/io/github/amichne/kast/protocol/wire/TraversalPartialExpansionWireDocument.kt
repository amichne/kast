package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TraversalPartialExpansionWireDocument(
    val subject: String,
    val depth: Int,
    val limitations: List<RelationLimitationWireDocument>,
    val remainder: TraversalExpansionRemainderWireDocument,
    val scope: TraversalExpansionScopeWireDocument = TraversalExpansionScopeWireDocument.PAGE,
)

@Serializable
internal enum class TraversalExpansionScopeWireDocument {
    @SerialName("page") PAGE
}

@Serializable
internal enum class TraversalExpansionRemainderWireDocument {
    @SerialName("continuation_retained") CONTINUATION_RETAINED,
    @SerialName("not_explored") NOT_EXPLORED,
}

internal fun TraversalPartialExpansionDocument.toWireDocument() =
    TraversalPartialExpansionWireDocument(
        subject.value,
        depth.value,
        limitations.map { it.toWireDocument() },
        when (remainder) {
            TraversalExpansionRemainderDocument.CONTINUATION_RETAINED ->
                TraversalExpansionRemainderWireDocument.CONTINUATION_RETAINED
            TraversalExpansionRemainderDocument.NOT_EXPLORED -> TraversalExpansionRemainderWireDocument.NOT_EXPLORED
        },
    )

internal fun TraversalPartialExpansionWireDocument.toContract():
    WireDocumentConversion<TraversalPartialExpansionDocument> =
    combineConverted(
            ProtocolText.parse(subject).toWireDocumentConversion(),
            TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
        ) { subject, depth ->
            subject to depth
        }
        .flatMapConverted { (subject, depth) ->
            TraversalPartialExpansionDocument.create(
                    subject,
                    depth,
                    limitations.map { it.toContract() },
                    when (remainder) {
                        TraversalExpansionRemainderWireDocument.CONTINUATION_RETAINED ->
                            TraversalExpansionRemainderDocument.CONTINUATION_RETAINED
                        TraversalExpansionRemainderWireDocument.NOT_EXPLORED ->
                            TraversalExpansionRemainderDocument.NOT_EXPLORED
                    },
                )
                .toWireDocumentConversion()
        }
