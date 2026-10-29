package io.github.amichne.kast.protocol.contract

internal fun ImpactValueConditionDocument.ReviewedCollectionNormalResult.admitsCollectionResult(): Boolean =
    binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 &&
        binding.admits(origin.execution.enclosing) &&
        binding.matchesReceiver(sourceSite()) &&
        useRange == binding.supplierRange

internal fun ImpactValueConditionDocument.ReviewedEntryKeyUnique.admitsEntryKeyUnique(): Boolean =
    entry.keyEvidence == ImpactReviewedEntryKeyEvidenceDocument.Conditional &&
        entry.admits(origin.execution.enclosing.basis) &&
        entry.matchesReturn(sourceSite()) &&
        origin.valuePath.values.firstOrNull().let {
            it is ImpactValuePathSegmentDocument.PairComponent && it.origin == entry.tupleOrigin
        } &&
        useRange == entry.binding.supplierRange

internal fun ImpactValueSiteReferenceDocument.conservesCollectionConditions(
    target: ImpactValueSiteReferenceDocument,
    binding: ImpactReviewedValueBindingDocument,
): Boolean =
    preservesBeforeAddedCondition(target) &&
        when (val added = target.conditions.values.last()) {
            is ImpactValueConditionDocument.ReviewedCollectionNormalResult ->
                added.binding == binding && added.matchesOrigin(this, target.range)
            else -> false
        }

internal fun ImpactValueSiteReferenceDocument.conservesEntryConditions(
    target: ImpactValueSiteReferenceDocument,
    entry: ImpactReviewedMapEntryCorrelationDocument,
): Boolean =
    preservesBeforeAddedCondition(target) &&
        when (val added = target.conditions.values.last()) {
            is ImpactValueConditionDocument.ReviewedEntryKeyUnique ->
                added.entry == entry && added.matchesOrigin(this, target.range)
            else -> false
        }

private fun ImpactValueSiteReferenceDocument.preservesBeforeAddedCondition(
    target: ImpactValueSiteReferenceDocument
): Boolean =
    target.conditions.values.size == conditions.values.size + 1 &&
        conditions.values == target.conditions.values.dropLast(1)

private fun ImpactValueConditionDocument.matchesOrigin(
    source: ImpactValueSiteReferenceDocument,
    resultRange: ImpactSourceRangeDocument,
): Boolean =
    sourceSite().hasSameValueIdentity(source.copy(conditions = EmptyImpactConditions)) && useRange == resultRange
