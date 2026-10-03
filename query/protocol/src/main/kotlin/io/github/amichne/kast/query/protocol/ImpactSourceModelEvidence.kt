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
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactSourceFailure
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
)

private data class ImpactSourceModels(val representation: List<RepresentationRule>, val boundaries: List<BoundaryModel>)

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
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return impactFailure(
                    when (admitted.failure) {
                        QueryImpactSourceFailure.EMPTY_PRODUCERS -> QueryImpactSourceFailureCode.EMPTY_PRODUCERS
                        QueryImpactSourceFailure.DUPLICATE_PRODUCER -> QueryImpactSourceFailureCode.DUPLICATE_PRODUCER
                        QueryImpactSourceFailure.DUPLICATE_MODEL ->
                            QueryImpactSourceFailureCode.DUPLICATE_MODEL_REFERENCE
                        QueryImpactSourceFailure.FOREIGN_BASIS -> QueryImpactSourceFailureCode.BASIS_MISMATCH
                    }
                )
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
    for ((position, syntax) in modelSyntax.withIndex()) {
        val model = syntax.document
        when (model) {
            is ImpactModelDocument.Representation ->
                when (val bound = AdmittedRepresentationModel.admit(syntax, evidence.declarations)) {
                    is Refinement.Refined -> representation += bound.value.rules
                    is Refinement.Rejected -> return impactFailure(bound.failure.impactFailure(), position)
                }
            is ImpactModelDocument.Boundary ->
                when (val bound = AdmittedBoundaryModel.admit(syntax, evidence.positions)) {
                    is Refinement.Refined -> boundaries += bound.value.rules
                    is Refinement.Rejected -> return impactFailure(bound.failure.impactFailure(), position)
                }
        }
    }
    return Refinement.Refined(ImpactSourceModels(representation.toList(), boundaries.toList()))
}
