package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder
import io.github.amichne.kast.traversal.contract.TraversalPartialExpansion

internal fun List<TraversalPartialExpansion>.protocolDocuments(
    authority: QueryReferenceAuthority
): Refinement<BoundedProtocolList<TraversalPartialExpansionDocument>, TraversalRunRejection> {
    val documents = mutableListOf<TraversalPartialExpansionDocument>()
    for (partial in this) {
        val reference =
            when (val issued = authority.issueEndpoint(partial.entry.node.endpoint)) {
                is RelationEndpointIssuance.Issued -> issued.selector
                is RelationEndpointIssuance.Rejected -> return Refinement.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
        val depth =
            when (val admitted = TraversalDepthDocument.parse(partial.entry.depth.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(TraversalRunRejection.PLAN_REJECTED)
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
            is Refinement.Rejected -> return Refinement.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
    }
    return when (val bounded = BoundedProtocolList.create(documents)) {
        is Refinement.Refined -> bounded
        is Refinement.Rejected -> Refinement.Rejected(TraversalRunRejection.PLAN_REJECTED)
    }
}
