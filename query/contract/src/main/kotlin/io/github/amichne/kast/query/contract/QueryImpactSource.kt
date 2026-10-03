package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections

enum class QueryImpactProducerFailure {
    NOT_INVOCATION_RESULT
}

/** A seed retains the compiler-confirmed invocation which produced this exact expression result. */
class QueryImpactProducer private constructor(val site: ValueSite, val invocation: ValueInvocation) {
    companion object {
        fun admit(
            site: ValueSite,
            invocation: ValueInvocation,
        ): Refinement<QueryImpactProducer, QueryImpactProducerFailure> =
            if (site != invocation.resultSite()) Refinement.Rejected(QueryImpactProducerFailure.NOT_INVOCATION_RESULT)
            else Refinement.Refined(QueryImpactProducer(site, invocation))
    }
}

enum class QueryImpactSourceFailure {
    EMPTY_PRODUCERS,
    DUPLICATE_PRODUCER,
    DUPLICATE_MODEL,
    FOREIGN_BASIS,
    DUPLICATE_REQUESTED_SITE,
    DUPLICATE_PEER_BOUNDARY,
    UNDECLARED_PEER_BOUNDARY,
}

/** One admitted finite question, on one current authority, interpreted by the existing query execution owner. */
class QueryImpactSource
private constructor(
    val producers: List<QueryImpactProducer>,
    val representationModels: List<RepresentationRule>,
    val boundaryModels: List<BoundaryModel>,
    val domain: RelationSearchBoundary,
    val semantics: QueryImpactFlowSemantics,
    val lease: SemanticReadAuthority,
    val requestedSites: List<QueryImpactRequestedSite>,
    val peerBoundaries: List<QueryImpactPeerBoundary>,
) {
    /** Native observation admission retains the full current authority, beyond equal epoch identifiers. */
    fun admitObservation(step: ValueFlowStep): Refinement<ValueFlowStep, ValueFlowStepFailure> =
        if (step.hasForeignBasis(lease)) Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH)
        else Refinement.Refined(step)

    val retainedBytes: Long
        get() = QueryImpactRetainedGraph().source(this)

    companion object {
        fun admit(
            producers: List<QueryImpactProducer>,
            representationModels: List<RepresentationRule>,
            boundaryModels: List<BoundaryModel>,
            domain: RelationSearchBoundary,
            semantics: QueryImpactFlowSemantics = QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            requestedSites: List<QueryImpactRequestedSite> = emptyList(),
            peerBoundaries: List<QueryImpactPeerBoundary> = emptyList(),
        ): Refinement<QueryImpactSource, QueryImpactSourceFailure> {
            if (producers.isEmpty()) return Refinement.Rejected(QueryImpactSourceFailure.EMPTY_PRODUCERS)
            if (producers.map { it.site }.distinct().size != producers.size)
                return Refinement.Rejected(QueryImpactSourceFailure.DUPLICATE_PRODUCER)
            val references = representationModels.map { it.reference } + boundaryModels.map { it.reference }
            if (references.distinct().size != references.size)
                return Refinement.Rejected(QueryImpactSourceFailure.DUPLICATE_MODEL)
            if (requestedSites.map { it.site }.distinct().size != requestedSites.size)
                return Refinement.Rejected(QueryImpactSourceFailure.DUPLICATE_REQUESTED_SITE)
            val lease = producers.first().site.enclosing.lease
            if (producers.any { it.hasForeignBasis(lease) } || representationModels.any { it.hasForeignBasis(lease) })
                return Refinement.Rejected(QueryImpactSourceFailure.FOREIGN_BASIS)
            when (val admitted = admitPeerBoundaries(lease, boundaryModels, peerBoundaries)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        when (admitted.failure) {
                            QueryImpactPeerDeclarationFailure.DUPLICATE_BOUNDARY ->
                                QueryImpactSourceFailure.DUPLICATE_PEER_BOUNDARY
                            QueryImpactPeerDeclarationFailure.UNDECLARED_BOUNDARY ->
                                QueryImpactSourceFailure.UNDECLARED_PEER_BOUNDARY
                            QueryImpactPeerDeclarationFailure.FOREIGN_BASIS -> QueryImpactSourceFailure.FOREIGN_BASIS
                        }
                    )
            }
            if (requestedSites.any { it.site.hasForeignBasis(lease) })
                return Refinement.Rejected(QueryImpactSourceFailure.FOREIGN_BASIS)
            return Refinement.Refined(
                QueryImpactSource(
                    Collections.unmodifiableList(producers.toList()),
                    Collections.unmodifiableList(representationModels.toList()),
                    Collections.unmodifiableList(boundaryModels.toList()),
                    domain,
                    semantics,
                    lease,
                    Collections.unmodifiableList(requestedSites.toList()),
                    Collections.unmodifiableList(peerBoundaries.toList()),
                )
            )
        }
    }
}

private fun QueryImpactProducer.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    site.hasForeignBasis(lease) || invocation.hasForeignBasis(lease)
