package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint

internal data class ImpactSourceModelEvidence(
    val producers: List<QueryImpactProducer>,
    val declarations: List<RevalidatedRelationEndpoint>,
    val positions: List<BoundaryPosition>,
    val examinedWork: Long,
    val requestedSites: List<QueryImpactRequestedSite>,
    val peers: List<io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission>,
)

private data class ImpactSourceModels(
    val representation: List<RepresentationRule>,
    val boundaries: List<BoundaryModel>,
    val peers: List<io.github.amichne.kast.query.contract.QueryImpactPeerBoundary>,
)

internal fun bindImpactSourceModels(
    evidence: ImpactSourceModelEvidence,
    modelSyntax: List<AdmittedImpactModelSyntax>,
    domain: RelationSearchBoundary,
    flow: QueryImpactFlowDocument,
    budget: QueryBudget,
): Refinement<QueryImpactSourceAdmission, QueryRunRejection> {
    val models =
        when (val admitted = admitModels(modelSyntax, evidence)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val source =
        when (
            val admitted =
                QueryImpactSource.admit(
                    evidence.producers,
                    models.representation,
                    models.boundaries,
                    domain,
                    when (flow) {
                        QueryImpactFlowDocument.KOTLIN_FORWARD_V1 -> QueryImpactFlowSemantics.KOTLIN_FORWARD_V1
                    },
                    evidence.requestedSites,
                    models.peers,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return impactFailure(admitted.failure.impactFailure())
        }
    if (source.retainedBytes > budget.checkpointBytes.value)
        return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED)
    return Refinement.Refined(QueryImpactSourceAdmission(source, evidence.examinedWork))
}

private fun admitModels(
    modelSyntax: List<AdmittedImpactModelSyntax>,
    evidence: ImpactSourceModelEvidence,
): Refinement<ImpactSourceModels, QueryRunRejection> {
    val representation = mutableListOf<RepresentationRule>()
    val boundaries = mutableListOf<BoundaryModel>()
    val peers = mutableListOf<io.github.amichne.kast.query.contract.QueryImpactPeerBoundary>()
    for ((position, syntax) in modelSyntax.withIndex()) {
        val model = syntax.document
        when (model) {
            is ImpactModelDocument.Representation ->
                when (val bound = AdmittedRepresentationModel.admit(syntax, evidence.declarations)) {
                    is Refinement.Refined -> representation += bound.value.rules
                    is Refinement.Rejected -> return impactFailure(bound.failure.impactFailure(), position)
                }
            is ImpactModelDocument.Boundary ->
                when (val bound = admitBoundaryModels(syntax, evidence, position)) {
                    is Refinement.Refined -> {
                        boundaries += bound.value.boundaries
                        peers += bound.value.peers
                    }
                    is Refinement.Rejected -> return bound
                }
        }
    }
    return Refinement.Refined(ImpactSourceModels(representation.toList(), boundaries.toList(), peers.toList()))
}

private fun admitBoundaryModels(
    syntax: AdmittedImpactModelSyntax,
    evidence: ImpactSourceModelEvidence,
    position: Int,
): Refinement<ImpactSourceModels, QueryRunRejection> {
    val rules =
        when (val bound = AdmittedBoundaryModel.admit(syntax, evidence.positions)) {
            is Refinement.Refined -> bound.value.rules
            is Refinement.Rejected -> return impactFailure(bound.failure.impactFailure(), position)
        }
    val peers =
        when (val admitted = admitPeerModelBoundaries(rules, evidence, position)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return Refinement.Refined(ImpactSourceModels(emptyList(), rules, peers))
}

private fun admitPeerModelBoundaries(
    boundaries: List<BoundaryModel>,
    evidence: ImpactSourceModelEvidence,
    position: Int,
): Refinement<List<io.github.amichne.kast.query.contract.QueryImpactPeerBoundary>, QueryRunRejection> {
    val peers = mutableListOf<io.github.amichne.kast.query.contract.QueryImpactPeerBoundary>()
    val sourceAuthority = evidence.producers.first().site.enclosing.lease
    for (model in boundaries) {
        if (
            model is BoundaryModel.Continuation &&
                model.target.site.enclosing.lease.identity != sourceAuthority.identity
        ) {
            val target =
                evidence.peers.singleOrNull { it.site == model.target.site }
                    ?: return impactFailure(QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH, position)
            when (
                val admitted =
                    io.github.amichne.kast.query.contract.QueryImpactPeerBoundary.admit(sourceAuthority, model, target)
            ) {
                is Refinement.Refined -> peers += admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(admitted.failure.peerRejection(queryPosition(position)))
            }
        }
    }
    return Refinement.Refined(peers.toList())
}
