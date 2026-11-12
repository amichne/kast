package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.relation.contract.ValueFlowRejection

internal enum class ImpactSiteAdmissionOrigin {
    BOUNDARY_MODEL,
    REQUESTED_SITE,
    PEER_BOUNDARY,
}

internal fun ValueFlowRejection.siteFailure(
    origin: ImpactSiteAdmissionOrigin,
    position: Int,
): Refinement.Rejected<QueryRunRejection> =
    when (this) {
        is ValueFlowRejection.Native -> nativeImpactFailure(position)
        ValueFlowRejection.STALE_SITE ->
            impactFailure(
                if (origin == ImpactSiteAdmissionOrigin.REQUESTED_SITE)
                    QueryImpactSourceFailureCode.STALE_REQUESTED_SITE
                else QueryImpactSourceFailureCode.STALE_BOUNDARY_SITE,
                position,
            )
        ValueFlowRejection.UNSUPPORTED_SEED ->
            impactFailure(
                if (origin == ImpactSiteAdmissionOrigin.REQUESTED_SITE)
                    QueryImpactSourceFailureCode.UNSUPPORTED_REQUESTED_SITE
                else QueryImpactSourceFailureCode.UNSUPPORTED_BOUNDARY_SITE,
                position,
            )
        ValueFlowRejection.UNRESOLVED_SEED ->
            impactFailure(
                if (origin == ImpactSiteAdmissionOrigin.REQUESTED_SITE)
                    QueryImpactSourceFailureCode.UNRESOLVED_REQUESTED_SITE
                else QueryImpactSourceFailureCode.UNRESOLVED_BOUNDARY_SITE,
                position,
            )
        ValueFlowRejection.OUTSIDE_DOMAIN -> impactFailure(QueryImpactSourceFailureCode.OUTSIDE_DOMAIN, position)
        ValueFlowRejection.OWNER_UNAVAILABLE -> impactFailure(QueryImpactSourceFailureCode.OWNER_UNAVAILABLE, position)
        ValueFlowRejection.AUTHORITY_MOVED -> impactFailure(QueryImpactSourceFailureCode.AUTHORITY_MOVED, position)
        ValueFlowRejection.GRANT_TOO_SMALL -> impactFailure(QueryImpactSourceFailureCode.GRANT_TOO_SMALL, position)
        ValueFlowRejection.NESTED_EXECUTION -> impactFailure(QueryImpactSourceFailureCode.NESTED_EXECUTION, position)
    }
