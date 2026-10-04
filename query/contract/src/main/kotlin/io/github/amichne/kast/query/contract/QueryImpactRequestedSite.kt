package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationFailure
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim

sealed interface QueryImpactRequestedSiteFailure {
    data class Proof(val cause: ValueSiteRevalidationFailure) : QueryImpactRequestedSiteFailure

    data object WorkReceiptExceedsGrant : QueryImpactRequestedSiteFailure
}

/** Native selection proof and observed admission grant; selection proves no producer relationship. */
class QueryImpactRequestedSite
private constructor(
    val request: ValueSiteRevalidationRequest,
    val proof: RevalidatedValueSite,
    val examinedWorkUnits: RelationWorkCount,
) {
    val site: ValueSite
        get() = proof.site

    companion object {
        fun admit(
            request: ValueSiteRevalidationRequest,
            proof: RevalidatedValueSite,
            examinedWorkUnits: RelationWorkCount,
        ): Refinement<QueryImpactRequestedSite, QueryImpactRequestedSiteFailure> {
            if (examinedWorkUnits.value > request.budget.resources.workUnitLimit.value)
                return Refinement.Rejected(QueryImpactRequestedSiteFailure.WorkReceiptExceedsGrant)
            val argument = request.role as? ValueSiteRoleClaim.Argument
            if (
                proof.site.hasForeignBasis(request.enclosing.lease) ||
                    (argument != null && argument.expectedCallable.lease != request.enclosing.lease)
            )
                return Refinement.Rejected(
                    QueryImpactRequestedSiteFailure.Proof(ValueSiteRevalidationFailure.BASIS_MISMATCH)
                )
            when (val admitted = RevalidatedValueSite.fromCompiler(request, proof.site)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected ->
                    return Refinement.Rejected(QueryImpactRequestedSiteFailure.Proof(admitted.failure))
            }
            return Refinement.Refined(QueryImpactRequestedSite(request, proof, examinedWorkUnits))
        }
    }
}
