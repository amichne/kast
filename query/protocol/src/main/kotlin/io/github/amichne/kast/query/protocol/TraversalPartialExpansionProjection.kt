package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.query.contract.QueryWalkPartialExpansion
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder

internal fun List<QueryWalkPartialExpansion>.protocolDocuments(
    authority: QueryReferenceAuthority
): BoundedProtocolList<TraversalPartialExpansionDocument>? {
    val documents = mutableListOf<TraversalPartialExpansionDocument>()
    for (partial in this) {
        val reference =
            when (val issued = authority.issueEndpoint(partial.entry.node.endpoint)) {
                is RelationEndpointIssuance.Issued -> issued.selector
                is RelationEndpointIssuance.Rejected -> return null
            }
        val depth =
            when (val admitted = TraversalDepthDocument.parse(partial.entry.depth.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return null
            }
        val document =
            TraversalPartialExpansionDocument.create(
                reference,
                depth,
                partial.limitations.map(RelationLimitation::protocolDocument),
                when (partial.remainder) {
                    TraversalExpansionRemainder.CONTINUATION_RETAINED ->
                        TraversalExpansionRemainderDocument.CONTINUATION_RETAINED
                    TraversalExpansionRemainder.NOT_EXPLORED -> TraversalExpansionRemainderDocument.NOT_EXPLORED
                },
            )
        when (document) {
            is Refinement.Refined -> documents += document.value
            is Refinement.Rejected -> return null
        }
    }
    return when (val bounded = BoundedProtocolList.create(documents)) {
        is Refinement.Refined -> bounded.value
        is Refinement.Rejected -> null
    }
}
