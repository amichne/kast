package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import java.util.Collections

/** Pairs completed child reads with precisely the foreign targets selected from this request. */
internal class ImpactPeerSourceEvidence
private constructor(
    selections: List<QueryImpactPeerSelection>,
    admissions: List<QueryImpactPeerSiteAdmission>,
) {
    val selections = Collections.unmodifiableList(selections.toList())
    val admissions = Collections.unmodifiableList(admissions.toList())
    val retainedBytes: Long =
        QueryImpactRetainedGraph().let { graph ->
            this.admissions.fold(0L) { bytes, admission ->
                saturatingImpactAdmissionCount(bytes, graph.peerSiteAdmission(admission))
            }
        }
    val examinedWork: Long =
        this.admissions.fold(0L) { work, admission ->
            saturatingImpactAdmissionCount(work, admission.acquisition.examinedWorkUnits.value)
        }

    companion object {
        fun admit(
            selections: List<QueryImpactPeerSelection>,
            admissions: List<QueryImpactPeerSiteAdmission>,
        ): Refinement<ImpactPeerSourceEvidence, QueryRunRejection> {
            if (admissions.map { it.site }.distinct().size != admissions.size)
                return impactFailure(QueryImpactSourceFailureCode.DUPLICATE_PEER_BOUNDARY)
            if (admissions.any { proof -> selections.none { it.site.matchesSite(proof.site) } })
                return impactFailure(QueryImpactSourceFailureCode.UNDECLARED_PEER_BOUNDARY)
            for (selected in selections) {
                if (admissions.none { selected.site.matchesSite(it.site) })
                    return impactFailure(
                        QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
                        selected.modelPosition.value,
                    )
            }
            return Refinement.Refined(ImpactPeerSourceEvidence(selections, admissions))
        }
    }
}

/** Nonnegative observed counts may exhaust a grant, but must never wrap into permission for further work. */
internal fun saturatingImpactAdmissionCount(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
