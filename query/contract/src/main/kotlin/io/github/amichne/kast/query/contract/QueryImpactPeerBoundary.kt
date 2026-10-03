package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Allows one reviewed modeled edge to a completed peer selection; it permits no peer native expansion. */
class QueryImpactPeerBoundary
private constructor(
    val model: BoundaryModel.Continuation,
    val target: QueryImpactPeerSiteAdmission,
) {
    fun admitConnection(
        connection: BoundaryArrival.Connected
    ): Refinement<BoundaryArrival.Connected, QueryImpactPeerProofFailure> =
        if (
            connection.model != model ||
                connection.source.site.hasForeignBasis(model.source.site.enclosing.lease) ||
                connection.target.site.hasForeignBasis(target.acquisition.completedAuthority)
        )
            Refinement.Rejected(QueryImpactPeerProofFailure.CONNECTION_MISMATCH)
        else Refinement.Refined(connection)

    companion object {
        fun admit(
            sourceAuthority: SemanticReadAuthority,
            model: BoundaryModel.Continuation,
            target: QueryImpactPeerSiteAdmission,
        ): Refinement<QueryImpactPeerBoundary, QueryImpactPeerProofFailure> {
            if (model.source.site.hasForeignBasis(sourceAuthority))
                return Refinement.Rejected(QueryImpactPeerProofFailure.SOURCE_BASIS_MISMATCH)
            if (model.target.site.enclosing.lease.workspaceRoot == sourceAuthority.workspaceRoot)
                return Refinement.Rejected(QueryImpactPeerProofFailure.SAME_SOURCE_ROOT)
            if (model.target.site.hasForeignBasis(target.acquisition.completedAuthority))
                return Refinement.Rejected(QueryImpactPeerProofFailure.TARGET_BASIS_MISMATCH)
            if (model.target.site != target.site)
                return Refinement.Rejected(QueryImpactPeerProofFailure.TARGET_IDENTITY_MISMATCH)
            return Refinement.Refined(QueryImpactPeerBoundary(model, target))
        }
    }
}

/** Every foreign declaration is paired with its own completed target, including unused admitted models. */
internal fun admitPeerBoundaries(
    sourceAuthority: SemanticReadAuthority,
    models: List<BoundaryModel>,
    peers: List<QueryImpactPeerBoundary>,
): Refinement<Unit, QueryImpactPeerDeclarationFailure> {
    if (peers.map { it.model.reference }.distinct().size != peers.size)
        return Refinement.Rejected(QueryImpactPeerDeclarationFailure.DUPLICATE_BOUNDARY)
    if (peers.any { it.model !in models })
        return Refinement.Rejected(QueryImpactPeerDeclarationFailure.UNDECLARED_BOUNDARY)
    for (model in models) {
        if (model.source.site.hasForeignBasis(sourceAuthority))
            return Refinement.Rejected(QueryImpactPeerDeclarationFailure.FOREIGN_BASIS)
        when (model) {
            is BoundaryModel.Terminal -> Unit
            is BoundaryModel.Continuation ->
                when (val admitted = admitContinuationTarget(sourceAuthority, model, peers)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
        }
    }
    return Refinement.Refined(Unit)
}

private fun admitContinuationTarget(
    sourceAuthority: SemanticReadAuthority,
    model: BoundaryModel.Continuation,
    peers: List<QueryImpactPeerBoundary>,
): Refinement<Unit, QueryImpactPeerDeclarationFailure> {
    if (!model.target.site.hasForeignBasis(sourceAuthority)) return Refinement.Refined(Unit)
    val peer =
        peers.singleOrNull { it.model == model }
            ?: return Refinement.Rejected(QueryImpactPeerDeclarationFailure.FOREIGN_BASIS)
    return when (QueryImpactPeerBoundary.admit(sourceAuthority, model, peer.target)) {
        is Refinement.Refined -> Refinement.Refined(Unit)
        is Refinement.Rejected -> Refinement.Rejected(QueryImpactPeerDeclarationFailure.FOREIGN_BASIS)
    }
}
