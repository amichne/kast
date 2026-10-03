package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** A detached presentation slice preserves original evidence and can only weaken closure qualification. */
internal fun QueryRunResult.selectedImpactAccounting(
    selected: List<QueryResultItemDocument>,
    dropped: Int,
): Refinement<ImpactAccountingDocument, ImpactAccountingFailure> {
    when (val valid = validateImpactAccounting()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return valid
    }
    val pathCount =
        when (
            val count =
                QueryDiscoveryCountDocument.parse(selected.count { it is QueryResultItemDocument.ValuePath }.toLong())
        ) {
            is Refinement.Refined -> count.value
            is Refinement.Rejected -> return Refinement.Rejected(ImpactAccountingFailure.PAGE_COUNT_MISMATCH)
        }
    return when (val accounting = impactAccounting) {
        ImpactAccountingDocument.NotApplicable -> Refinement.Refined(accounting)
        is ImpactAccountingDocument.EvidenceOnly -> Refinement.Refined(accounting.copy(pagePathCount = pathCount))
        is ImpactAccountingDocument.Investigated -> {
            val status = accounting.status.selectedStatus(selected.size == items.values.size)
            val view =
                when (val admitted = accounting.view.selectedView(dropped, selected.size)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            Refinement.Refined(accounting.copy(pagePathCount = pathCount, status = status, view = view))
        }
    }
}

private fun ImpactAccountingStatusDocument.selectedStatus(fullPage: Boolean): ImpactAccountingStatusDocument =
    when (this) {
        ImpactAccountingStatusDocument.Conserved ->
            if (fullPage) this else ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged)
        is ImpactAccountingStatusDocument.Unresolved ->
            if (fullPage) this
            else ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Unresolved(required))
        is ImpactAccountingStatusDocument.SelectedSubset -> this
    }

private fun ImpactAccountingViewDocument.selectedView(
    dropped: Int,
    size: Int,
): Refinement<ImpactAccountingViewDocument, ImpactAccountingFailure> =
    when (this) {
        ImpactAccountingViewDocument.Paths -> Refinement.Refined(this)
        is ImpactAccountingViewDocument.Witness -> selectedWitnessView(dropped, size)
    }

private fun ImpactAccountingViewDocument.Witness.selectedWitnessView(
    dropped: Int,
    size: Int,
): Refinement<ImpactAccountingViewDocument, ImpactAccountingFailure> {
    val first =
        when (val admitted = QueryDiscoveryCountDocument.parse(firstOrdinal.value + dropped)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
        }
    val next =
        when (val admitted = QueryDiscoveryCountDocument.parse(first.value + size)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
        }
    return Refinement.Refined(copy(firstOrdinal = first, nextOrdinal = next))
}
