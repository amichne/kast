package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ImpactFindingEvidenceReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAdmissionDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteExclusionDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteOutcomeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.query.contract.QueryImpactExclusionCause
import io.github.amichne.kast.query.contract.QueryImpactSiteAccounting
import io.github.amichne.kast.query.contract.QueryImpactSiteExclusion
import io.github.amichne.kast.query.contract.QueryImpactSiteOutcome
import io.github.amichne.kast.query.contract.QueryRetainedRowOrdinal

internal fun QueryImpactSiteAccounting.siteAccountingDocument(
    originalPathRowIds: List<QueryResultRowReference>?
): ImpactProjected<ImpactSiteAccountingDocument> =
    count(requestedSiteOrdinal.value.toLong())
        .impactZip(requested.site.impactDocument())
        .impactZip(outcome.siteOutcomeDocument(originalPathRowIds))
        .impactZip(count(requested.examinedWorkUnits.value))
        .impactZip(
            ReturnedByteLimit.parse(requested.request.budget.returnedBytes.value)
                .impactFailure(ImpactPathProjectionFailure::Budget)
        )
        .impactMap { (values, bytes) ->
            val budget = requested.request.budget.resources
            ImpactSiteAccountingDocument(
                values.first.first.first,
                values.first.first.second,
                values.first.second,
                ImpactSiteAdmissionDocument(
                    ImpactFlowBudgetDocument(
                        budget.elapsedTimeLimit,
                        budget.workUnitLimit,
                        budget.resultLimit,
                        bytes,
                    ),
                    values.second,
                ),
            )
        }

private fun QueryImpactSiteOutcome.siteOutcomeDocument(
    originalPathRowIds: List<QueryResultRowReference>?
): ImpactProjected<ImpactSiteOutcomeDocument> =
    when (this) {
        is QueryImpactSiteOutcome.Reached ->
            pathOrdinals
                .impactEach { it.linkDocument(originalPathRowIds) }
                .impactZip(exclusions.impactEach { it.exclusionDocument(originalPathRowIds) })
                .impactMap { (paths, exclusions) -> ImpactSiteOutcomeDocument.Reached(paths, exclusions) }
        is QueryImpactSiteOutcome.Excluded ->
            exclusions
                .impactEach { it.exclusionDocument(originalPathRowIds) }
                .impactMap(ImpactSiteOutcomeDocument::Excluded)
        QueryImpactSiteOutcome.RelationshipUnproven ->
            Refinement.Refined(ImpactSiteOutcomeDocument.RelationshipUnproven)
    }

private fun QueryImpactSiteExclusion.exclusionDocument(
    ids: List<QueryResultRowReference>?
): ImpactProjected<ImpactSiteExclusionDocument> =
    pathOrdinal.linkDocument(ids).impactZip(exclusion.domain.impactRequestedBoundary()).impactMap { (path, domain) ->
        ImpactSiteExclusionDocument(
            path,
            domain,
            when (exclusion.cause) {
                QueryImpactExclusionCause.OUTSIDE_EXACT_FILE -> ImpactScopeExclusionDocument.OUTSIDE_EXACT_FILE
                QueryImpactExclusionCause.OUTSIDE_DIRECTORY -> ImpactScopeExclusionDocument.OUTSIDE_DIRECTORY
            },
        )
    }

private fun QueryRetainedRowOrdinal.linkDocument(
    ids: List<QueryResultRowReference>?
): ImpactProjected<ImpactFindingEvidenceReferenceDocument> {
    val rowId =
        ids?.getOrNull(value) ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return count(value.toLong()).impactMap { ImpactFindingEvidenceReferenceDocument(it, rowId) }
}

private fun count(raw: Long): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(raw).impactFailure(ImpactPathProjectionFailure::Count)
