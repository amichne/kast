package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.relation.contract.ValueFlowRejection

internal enum class ImpactSiteAdmissionOrigin {
    BOUNDARY_MODEL,
    REQUESTED_SITE,
    PEER_BOUNDARY,
}

internal fun ValueFlowRejection.siteFailure(origin: ImpactSiteAdmissionOrigin): QueryImpactSourceFailureCode =
    when (origin) {
        ImpactSiteAdmissionOrigin.BOUNDARY_MODEL,
        ImpactSiteAdmissionOrigin.PEER_BOUNDARY -> boundaryFailure()
        ImpactSiteAdmissionOrigin.REQUESTED_SITE ->
            when (this) {
                ValueFlowRejection.STALE_SITE -> QueryImpactSourceFailureCode.STALE_REQUESTED_SITE
                ValueFlowRejection.UNSUPPORTED_SEED -> QueryImpactSourceFailureCode.UNSUPPORTED_REQUESTED_SITE
                ValueFlowRejection.UNRESOLVED_SEED -> QueryImpactSourceFailureCode.UNRESOLVED_REQUESTED_SITE
                ValueFlowRejection.OUTSIDE_DOMAIN -> QueryImpactSourceFailureCode.OUTSIDE_DOMAIN
                ValueFlowRejection.OWNER_UNAVAILABLE -> QueryImpactSourceFailureCode.OWNER_UNAVAILABLE
                ValueFlowRejection.AUTHORITY_MOVED -> QueryImpactSourceFailureCode.AUTHORITY_MOVED
                ValueFlowRejection.NATIVE_UNAVAILABLE -> QueryImpactSourceFailureCode.NATIVE_UNAVAILABLE
                ValueFlowRejection.GRANT_TOO_SMALL -> QueryImpactSourceFailureCode.GRANT_TOO_SMALL
                ValueFlowRejection.NESTED_EXECUTION -> QueryImpactSourceFailureCode.NESTED_EXECUTION
            }
    }
