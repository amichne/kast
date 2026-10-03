package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactRequestedSiteFailure
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest

internal suspend fun revalidateImpactSite(
    request: ValueSiteRevalidationRequest,
    compiler: ValueProducerSeedCompilerPort,
    grant: RelationBudget,
    origin: ImpactSiteAdmissionOrigin,
    position: Int,
): Refinement<QueryImpactRequestedSite, QueryRunRejection> {
    val read =
        when (val observed = compiler.revalidateSite(request)) {
            is ValueModelSiteRead.Revalidated -> observed
            is ValueModelSiteRead.Rejected -> return impactFailure(observed.cause.siteFailure(origin), position)
            is ValueModelSiteRead.ContractRejected -> return impactFailure(observed.cause.impactFailure(), position)
            is ValueModelSiteRead.Unsupported -> return impactFailure(observed.cause.boundaryFailure(), position)
            is ValueModelSiteRead.Limited -> return impactFailure(observed.cause.impactFailure(), position)
        }
    if (read.examinedWorkUnits.value > grant.resources.workUnitLimit.value)
        return impactFailure(QueryImpactSourceFailureCode.WORK_RECEIPT_EXCEEDS_GRANT, position)
    val actual =
        when (val admitted = RevalidatedValueSite.fromCompiler(request, read.position.site)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return impactFailure(admitted.failure.impactFailure(), position)
        }
    return when (val admitted = QueryImpactRequestedSite.admit(request, actual, read.examinedWorkUnits)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected ->
            impactFailure(
                when (val failure = admitted.failure) {
                    is QueryImpactRequestedSiteFailure.Proof -> failure.cause.impactFailure()
                    QueryImpactRequestedSiteFailure.WorkReceiptExceedsGrant ->
                        QueryImpactSourceFailureCode.WORK_RECEIPT_EXCEEDS_GRANT
                },
                position,
            )
    }
}
