package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadContractRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure

internal fun QueryImpactReadRejection.impactDocument(): ImpactProjected<ImpactReadRejectionDocument> =
    source
        .impactDocument()
        .impactZip(
            QueryDiscoveryCountDocument.parse(examinedWorkUnits.value).impactFailure(ImpactPathProjectionFailure::Count)
        )
        .impactZip(domain.impactRequestedBoundary())
        .impactMap { (evidence, requestedDomain) ->
            val (site, work) = evidence
            when (this) {
                is QueryImpactReadRejection.Native ->
                    ImpactReadRejectionDocument.Native(
                        site,
                        requestedDomain,
                        cause.impactDocument(),
                        work,
                    )
                is QueryImpactReadRejection.Contract ->
                    ImpactReadRejectionDocument.Contract(
                        site,
                        requestedDomain,
                        cause.impactDocument(),
                        work,
                    )
            }
        }

private fun ValueFlowRejection.impactDocument(): ImpactNativeReadRejectionDocument =
    when (this) {
        ValueFlowRejection.STALE_SITE -> ImpactNativeReadRejectionDocument.STALE_SITE
        ValueFlowRejection.OUTSIDE_DOMAIN -> ImpactNativeReadRejectionDocument.OUTSIDE_DOMAIN
        ValueFlowRejection.OWNER_UNAVAILABLE -> ImpactNativeReadRejectionDocument.OWNER_UNAVAILABLE
        ValueFlowRejection.AUTHORITY_MOVED -> ImpactNativeReadRejectionDocument.AUTHORITY_MOVED
        ValueFlowRejection.UNSUPPORTED_SEED -> ImpactNativeReadRejectionDocument.UNSUPPORTED_SEED
        ValueFlowRejection.UNRESOLVED_SEED -> ImpactNativeReadRejectionDocument.UNRESOLVED_SEED
        ValueFlowRejection.NATIVE_UNAVAILABLE -> ImpactNativeReadRejectionDocument.NATIVE_UNAVAILABLE
        ValueFlowRejection.GRANT_TOO_SMALL -> ImpactNativeReadRejectionDocument.GRANT_TOO_SMALL
        ValueFlowRejection.NESTED_EXECUTION -> ImpactNativeReadRejectionDocument.NESTED_EXECUTION
    }

private fun ValueFlowStepFailure.impactDocument(): ImpactReadContractRejectionDocument =
    when (this) {
        ValueFlowStepFailure.INVALID_PROGRESS -> ImpactReadContractRejectionDocument.INVALID_PROGRESS
        ValueFlowStepFailure.WORK_LIMIT_EXCEEDED -> ImpactReadContractRejectionDocument.WORK_LIMIT_EXCEEDED
        ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED -> ImpactReadContractRejectionDocument.RESULT_LIMIT_EXCEEDED
        ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED ->
            ImpactReadContractRejectionDocument.DETACHED_CAPACITY_EXCEEDED
        ValueFlowStepFailure.DOMAIN_MISMATCH -> ImpactReadContractRejectionDocument.DOMAIN_MISMATCH
        ValueFlowStepFailure.SOURCE_MISMATCH -> ImpactReadContractRejectionDocument.SOURCE_MISMATCH
        ValueFlowStepFailure.BASIS_MISMATCH -> ImpactReadContractRejectionDocument.BASIS_MISMATCH
        ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS -> ImpactReadContractRejectionDocument.UNRESOLVED_OBLIGATIONS
        ValueFlowStepFailure.MISSING_OBLIGATION -> ImpactReadContractRejectionDocument.MISSING_OBLIGATION
    }
