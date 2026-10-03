package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Cross-field response admission supplements generated shape validation; it does not establish ledger authority. */
fun QueryRunResult.validateImpactAccounting(): Refinement<Unit, ImpactAccountingFailure> {
    val pathCount = items.values.count { it is QueryResultItemDocument.ValuePath }.toLong()
    if (impactAccounting == ImpactAccountingDocument.NotApplicable) return validateNonImpactAccounting(pathCount)
    if (question.output != QueryOutputDocument.ValuePaths)
        return Refinement.Rejected(ImpactAccountingFailure.ACCOUNTING_FOR_NON_VALUE_OUTPUT)
    val investigated = impactAccounting as? ImpactAccountingDocument.Investigated
    val source = question.from as? QueryFromDocument.Impact
    if (investigated != null && source != null && investigated.requestedSites != source.investigation.requestedSites)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    when (val admitted = validateImpactRows(pathCount)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    return when (val accounting = impactAccounting) {
        is ImpactAccountingDocument.EvidenceOnly -> validatePageCount(accounting.pagePathCount.value, pathCount)
        is ImpactAccountingDocument.Investigated -> accounting.validateInvestigation(pathCount)
        ImpactAccountingDocument.NotApplicable -> Refinement.Refined(Unit)
    }
}

private fun QueryRunResult.validateNonImpactAccounting(pathCount: Long): Refinement<Unit, ImpactAccountingFailure> =
    if (
        pathCount != 0L ||
            items.values.any { it is QueryResultItemDocument.ImpactWitness } ||
            question.output == QueryOutputDocument.ValuePaths
    )
        Refinement.Rejected(ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING)
    else Refinement.Refined(Unit)

private fun QueryRunResult.validateImpactRows(pathCount: Long): Refinement<Unit, ImpactAccountingFailure> {
    val accounting = impactAccounting as? ImpactAccountingDocument.Investigated
    val witnessView = accounting?.view as? ImpactAccountingViewDocument.Witness
    if (witnessView == null)
        return if (pathCount != items.values.size.toLong()) Refinement.Rejected(ImpactAccountingFailure.MIXED_ROW_KINDS)
        else Refinement.Refined(Unit)
    val witnessItems = items.values.filterIsInstance<QueryResultItemDocument.ImpactWitness>()
    if (pathCount != 0L || witnessItems.size != items.values.size)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    if (witnessItems.any { it.item.section != witnessView.section || it.item.witness.section() != witnessView.section })
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    if (accounting.status !is ImpactAccountingStatusDocument.SelectedSubset)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    when (val specific = accounting.validateWitnessSection(witnessView, witnessItems)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return specific
    }
    return witnessView.validateWitnessOrdinals(witnessItems)
}

private fun ImpactAccountingDocument.Investigated.validateWitnessSection(
    view: ImpactAccountingViewDocument.Witness,
    items: List<QueryResultItemDocument.ImpactWitness>,
): Refinement<Unit, ImpactAccountingFailure> =
    when (view.section) {
        ImpactWitnessSectionDocument.FINDINGS -> validateFindingReferences(view, items, originalPathCount.value)
        ImpactWitnessSectionDocument.SITE_ACCOUNTING -> validateSiteAccounting(view, items)
        ImpactWitnessSectionDocument.PRODUCERS,
        ImpactWitnessSectionDocument.MODELS,
        ImpactWitnessSectionDocument.NATIVE_READS,
        ImpactWitnessSectionDocument.READ_REJECTIONS -> Refinement.Refined(Unit)
    }

private fun validateFindingReferences(
    view: ImpactAccountingViewDocument.Witness,
    items: List<QueryResultItemDocument.ImpactWitness>,
    originalPathCount: Long,
): Refinement<Unit, ImpactAccountingFailure> {
    if (view.sectionCount.value != originalPathCount)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
    val findings = items.map { (it.item.witness as ImpactWitnessDocument.Finding).finding }
    if (
        findings.any {
            val representation = it.representation
            representation is ImpactFindingRepresentationDocument.Present && representation.branches.values.isEmpty()
        }
    )
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    val references = findings.map { it.path }
    if (
        items.zip(references).any { (item, reference) ->
            reference.pathOrdinal != item.item.ordinal || reference.pathOrdinal.value >= originalPathCount
        }
    )
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
    if (references.map { it.pathRowId }.distinct().size != references.size)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun ImpactAccountingViewDocument.Witness.validateWitnessOrdinals(
    items: List<QueryResultItemDocument.ImpactWitness>
): Refinement<Unit, ImpactAccountingFailure> {
    if (nextOrdinal.value < firstOrdinal.value || nextOrdinal.value > sectionCount.value)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
    if (nextOrdinal.value - firstOrdinal.value != items.size.toLong())
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
    if (items.withIndex().any { (index, item) -> item.item.ordinal.value != firstOrdinal.value + index })
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun validatePageCount(declared: Long, actual: Long): Refinement<Unit, ImpactAccountingFailure> =
    if (declared != actual) Refinement.Rejected(ImpactAccountingFailure.PAGE_COUNT_MISMATCH)
    else Refinement.Refined(Unit)

private fun ImpactAccountingDocument.Investigated.validateInvestigation(
    pathCount: Long
): Refinement<Unit, ImpactAccountingFailure> {
    when (val count = validatePageCount(pagePathCount.value, pathCount)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return count
    }
    if (originalPathCount.value < pathCount) return Refinement.Rejected(ImpactAccountingFailure.ORIGINAL_COUNT_UNDERRUN)
    if (requestedSites.values.distinct().size != requestedSites.values.size)
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    if (seeds.values.isEmpty()) return Refinement.Rejected(ImpactAccountingFailure.EMPTY_SEEDS)
    if (seeds.values.distinct().size != seeds.values.size)
        return Refinement.Rejected(ImpactAccountingFailure.DUPLICATE_SEED)
    val references = representationModelReferences.values + boundaryModelReferences.values
    if (references.distinct().size != references.size)
        return Refinement.Rejected(ImpactAccountingFailure.DUPLICATE_MODEL_REFERENCE)
    return status.validateStatus(originalPathCount.value, pathCount)
}

private fun ImpactAccountingStatusDocument.validateStatus(
    original: Long,
    actual: Long,
): Refinement<Unit, ImpactAccountingFailure> =
    when (this) {
        ImpactAccountingStatusDocument.Conserved ->
            if (original != actual) Refinement.Rejected(ImpactAccountingFailure.CONSERVED_COUNT_MISMATCH)
            else Refinement.Refined(Unit)
        is ImpactAccountingStatusDocument.Unresolved -> required.validateRequiredObligations()
        is ImpactAccountingStatusDocument.SelectedSubset ->
            when {
                original <= actual -> Refinement.Rejected(ImpactAccountingFailure.SUBSET_COUNT_MISMATCH)
                originalClosure is ImpactClosureDocument.Unresolved ->
                    originalClosure.required.validateRequiredObligations()
                else -> Refinement.Refined(Unit)
            }
    }

private fun BoundedProtocolList<ImpactRequiredObligationDocument>.validateRequiredObligations():
    Refinement<Unit, ImpactAccountingFailure> =
    if (values.isEmpty()) Refinement.Rejected(ImpactAccountingFailure.EMPTY_UNRESOLVED_OBLIGATIONS)
    else Refinement.Refined(Unit)

/** Completion is unavailable for bare path evidence or a selected/unresolved investigation. */
fun QueryRunResult.validateImpactCompletion(): Refinement<Unit, ImpactAccountingFailure> {
    when (val accounting = validateImpactAccounting()) {
        is Refinement.Rejected -> return accounting
        is Refinement.Refined -> Unit
    }
    if (question.output != QueryOutputDocument.ValuePaths) return Refinement.Refined(Unit)
    val accounting = impactAccounting
    return if (
        accounting is ImpactAccountingDocument.Investigated &&
            accounting.status == ImpactAccountingStatusDocument.Conserved
    )
        Refinement.Refined(Unit)
    else Refinement.Rejected(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED)
}

private fun ImpactWitnessDocument.section(): ImpactWitnessSectionDocument =
    when (this) {
        is ImpactWitnessDocument.SiteAccounting -> ImpactWitnessSectionDocument.SITE_ACCOUNTING
        is ImpactWitnessDocument.Finding -> ImpactWitnessSectionDocument.FINDINGS
        is ImpactWitnessDocument.Producer,
        is ImpactWitnessDocument.ProducerSiteOnly -> ImpactWitnessSectionDocument.PRODUCERS
        is ImpactWitnessDocument.RepresentationModel,
        is ImpactWitnessDocument.BoundaryModel -> ImpactWitnessSectionDocument.MODELS
        is ImpactWitnessDocument.NativeRead,
        is ImpactWitnessDocument.CompilerTransfer,
        is ImpactWitnessDocument.FlowObligation -> ImpactWitnessSectionDocument.NATIVE_READS
        is ImpactWitnessDocument.ReadRejection -> ImpactWitnessSectionDocument.READ_REJECTIONS
    }
