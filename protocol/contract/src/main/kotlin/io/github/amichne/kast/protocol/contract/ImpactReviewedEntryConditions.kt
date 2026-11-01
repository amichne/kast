package io.github.amichne.kast.protocol.contract

internal fun ImpactValueConditionDocument.ReviewedPreservedKeySurvives.admitsPreservedKey(): Boolean =
    entry.admits(origin.execution.enclosing.basis) &&
        entry.matchesReceiver(sourceSite()) &&
        admitsPreservedSelection() &&
        useRange == entry.family.binding.supplierRange

private fun ImpactValueConditionDocument.ReviewedPreservedKeySurvives.admitsPreservedSelection(): Boolean =
    if (origin.valuePath.values.isEmpty()) entry.selection == ImpactReviewedEntrySelectionDocument.CONTENT
    else
        (origin.valuePath.values.first() as? ImpactValuePathSegmentDocument.MapEntryComponent)?.slot ==
            ImpactMapEntrySlotDocument.KEY && entry.selection.preservesVisibleContent(sourceSite())

internal fun ImpactValueConditionDocument.ReviewedEntryComponentStable.admitsStableEntry(): Boolean =
    binding.admits(origin.execution.enclosing) &&
        entry.admitsSlot(binding.basis, slot) &&
        sourceSite().matchesEntryRead(binding, entry, slot) &&
        useRange == binding.invocation.range

internal fun ImpactValueSiteReferenceDocument.conservesPreservedKeyCondition(
    target: ImpactValueSiteReferenceDocument,
    entry: ImpactReviewedPreservedKeyOriginDocument,
): Boolean =
    preservesEntryPrefix(target) &&
        when (val added = target.conditions.values.last()) {
            is ImpactValueConditionDocument.ReviewedPreservedKeySurvives ->
                added.entry == entry && added.matchesEntryOrigin(this, target.range)
            else -> false
        }

internal fun ImpactValueSiteReferenceDocument.conservesEntryReadConditions(
    target: ImpactValueSiteReferenceDocument,
    proof: ImpactReviewedCallValueBindingDocument,
    entry: ImpactReviewedMapEntryOriginDocument,
    slot: ImpactMapEntrySlotDocument,
): Boolean {
    val addedCount = if (proof.needsEntryPrefixPremise()) 2 else 1
    if (
        target.conditions.values.size != conditions.values.size + addedCount ||
            conditions.values != target.conditions.values.dropLast(addedCount)
    )
        return false
    val stable =
        target.conditions.values[conditions.values.size] as? ImpactValueConditionDocument.ReviewedEntryComponentStable
            ?: return false
    if (!stable.matchesEntryProof(proof, entry, slot) || !stable.matchesEntryOrigin(this, target.range)) return false
    if (!proof.needsEntryPrefixPremise()) return true
    val prefix =
        target.conditions.values.last() as? ImpactValueConditionDocument.ReviewedEntryPrefixNormal ?: return false
    return prefix.binding == proof &&
        prefix.entry == entry &&
        prefix.slot == slot &&
        prefix.matchesEntryOrigin(this, target.range)
}

private fun ImpactValueConditionDocument.ReviewedEntryComponentStable.matchesEntryProof(
    proof: ImpactReviewedCallValueBindingDocument,
    expectedEntry: ImpactReviewedMapEntryOriginDocument,
    expectedSlot: ImpactMapEntrySlotDocument,
): Boolean = binding == proof && entry == expectedEntry && slot == expectedSlot

internal fun ImpactValueConditionDocument.ReviewedEntryPrefixNormal.admitsEntryPrefixNormal(): Boolean =
    binding.needsEntryPrefixPremise() &&
        binding.admits(origin.execution.enclosing) &&
        entry.admitsSlot(binding.basis, slot) &&
        sourceSite().matchesEntryRead(binding, entry, slot) &&
        useRange == binding.invocation.range

private fun ImpactValueSiteReferenceDocument.preservesEntryPrefix(target: ImpactValueSiteReferenceDocument): Boolean =
    target.conditions.values.size == conditions.values.size + 1 &&
        conditions.values == target.conditions.values.dropLast(1)

private fun ImpactValueConditionDocument.matchesEntryOrigin(
    source: ImpactValueSiteReferenceDocument,
    result: ImpactSourceRangeDocument,
): Boolean = sourceSite().hasSameValueIdentity(source.copy(conditions = EmptyImpactConditions)) && useRange == result
