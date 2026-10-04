package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueSite

/** A completed native target selection proves no producer relationship or permission to explore the peer. */
class QueryImpactPeerSiteAdmission
private constructor(
    val selection: QueryImpactRequestedSite,
    val acquisition: QueryImpactPeerAcquisitionReceipt,
) {
    val site: ValueSite
        get() = selection.site

    companion object {
        fun admit(
            selection: QueryImpactRequestedSite,
            acquisition: QueryImpactPeerAcquisitionReceipt,
        ): Refinement<QueryImpactPeerSiteAdmission, QueryImpactPeerProofFailure> {
            if (
                selection.site.hasForeignBasis(acquisition.completedAuthority) ||
                    selection.request.enclosing.lease != acquisition.completedAuthority
            )
                return Refinement.Rejected(QueryImpactPeerProofFailure.TARGET_BASIS_MISMATCH)
            if (selection.examinedWorkUnits.value > acquisition.examinedWorkUnits.value)
                return Refinement.Rejected(QueryImpactPeerProofFailure.SITE_WORK_EXCEEDS_ACQUISITION_WORK)
            val selected = selection.request.budget
            val child = acquisition.grant
            if (
                selected.resources.resultLimit.value > child.resources.resultLimit.value ||
                    selected.resources.workUnitLimit.value > child.resources.workUnitLimit.value ||
                    selected.resources.elapsedTimeLimit.value > child.resources.elapsedTimeLimit.value
            )
                return Refinement.Rejected(QueryImpactPeerProofFailure.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT)
            if (selected.returnedBytes.value > child.returnedBytes.value)
                return Refinement.Rejected(QueryImpactPeerProofFailure.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT)
            return Refinement.Refined(QueryImpactPeerSiteAdmission(selection, acquisition))
        }
    }
}
