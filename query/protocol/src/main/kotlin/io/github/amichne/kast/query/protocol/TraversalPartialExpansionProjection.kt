package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.query.contract.QueryWalkPartialExpansion
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder

internal fun List<QueryWalkPartialExpansion>.protocolDocuments(
    authority: QueryReferenceAuthority
): BoundedProtocolList<TraversalPartialExpansionDocument>? {
    val documents = map { partial -> partial.protocolDocument(authority) ?: return null }
    return BoundedProtocolList.create(documents).refinedExpansionOrNull()
}

private fun QueryWalkPartialExpansion.protocolDocument(
    authority: QueryReferenceAuthority
): TraversalPartialExpansionDocument? {
    val reference =
        when (val issued = authority.issueEndpoint(entry.node.endpoint)) {
            is RelationEndpointIssuance.Issued -> issued.selector
            is RelationEndpointIssuance.Rejected -> return null
        }
    val depth = TraversalDepthDocument.parse(entry.depth.value).refinedExpansionOrNull() ?: return null
    val minimum = QueryKnownMinimum.parse(knownMinimum.value).refinedExpansionOrNull() ?: return null
    val omissions =
        BoundedProtocolList.create(omissions.map { it.protocolDocument() ?: return null }).refinedExpansionOrNull()
            ?: return null
    return TraversalPartialExpansionDocument.create(
            reference,
            depth,
            limitations.map(RelationLimitation::protocolDocument),
            remainder.protocolDocument(),
            minimum,
            omissions,
        )
        .refinedExpansionOrNull()
}

private fun TraversalExpansionRemainder.protocolDocument(): TraversalExpansionRemainderDocument =
    when (this) {
        TraversalExpansionRemainder.CONTINUATION_RETAINED -> TraversalExpansionRemainderDocument.CONTINUATION_RETAINED
        TraversalExpansionRemainder.NOT_EXPLORED -> TraversalExpansionRemainderDocument.NOT_EXPLORED
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedExpansionOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
