package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactAccountingDocument.Investigated.validateSiteAccounting(
    view: ImpactAccountingViewDocument.Witness,
    items: List<QueryResultItemDocument.ImpactWitness>,
): Refinement<Unit, ImpactAccountingFailure> {
    if (view.sectionCount.value != requestedSites.values.size.toLong())
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
    for (item in items) {
        val accounting = (item.item.witness as ImpactWitnessDocument.SiteAccounting).accounting
        if (!accounting.matchesRequestedSite(item.item.ordinal, requestedSites.values))
            return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
        if (accounting.admission.examinedWorkUnits.value > accounting.admission.budget.maxWorkUnits.value)
            return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
        if (!accounting.outcome.matchesOriginalPaths(this))
            return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    }
    return Refinement.Refined(Unit)
}

private fun ImpactSiteAccountingDocument.matchesRequestedSite(
    ordinal: QueryDiscoveryCountDocument,
    requested: List<ImpactValueSiteReferenceDocument>,
): Boolean = requestedSiteOrdinal == ordinal && requested.getOrNull(ordinal.value.toInt()) == site

private fun ImpactSiteOutcomeDocument.matchesOriginalPaths(accounting: ImpactAccountingDocument.Investigated): Boolean =
    when (this) {
        is ImpactSiteOutcomeDocument.Reached -> {
            val links = paths.values
            links.isNotEmpty() &&
                links.validOriginalLinks(accounting.originalPathCount.value) &&
                exclusions.values.validExclusions(accounting) &&
                links.none { path ->
                    exclusions.values.any {
                        it.path.pathOrdinal == path.pathOrdinal || it.path.pathRowId == path.pathRowId
                    }
                }
        }
        is ImpactSiteOutcomeDocument.Excluded ->
            exclusions.values.isNotEmpty() && exclusions.values.validExclusions(accounting)
        ImpactSiteOutcomeDocument.RelationshipUnproven -> accounting.hasRelationshipObligation()
    }

private fun List<ImpactFindingEvidenceReferenceDocument>.validOriginalLinks(originalCount: Long): Boolean =
    none { it.pathOrdinal.value >= originalCount } &&
        map { it.pathOrdinal }.distinct().size == size &&
        map { it.pathRowId }.distinct().size == size

private fun List<ImpactSiteExclusionDocument>.validExclusions(
    accounting: ImpactAccountingDocument.Investigated
): Boolean =
    all { it.domain is ImpactRequestedBoundaryDocument.SourceDomain && it.domain == accounting.requestedDomain } &&
        map { it.path }.validOriginalLinks(accounting.originalPathCount.value)

private fun ImpactAccountingDocument.Investigated.hasRelationshipObligation(): Boolean {
    val subset = status as? ImpactAccountingStatusDocument.SelectedSubset
    val closure = subset?.originalClosure as? ImpactClosureDocument.Unresolved
    return closure != null && ImpactRequiredObligationDocument.REQUESTED_SITE_RELATIONSHIP in closure.required.values
}
