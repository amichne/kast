package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedValueTransferDocument.admitsFiltered(
    relation: ImpactReviewedValueRelationDocument.FilteredElements
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    val path =
        when (relation.selection) {
            ImpactReviewedCollectionSelectionDocument.ELEMENT ->
                source.valuePath.values.firstOrNull() == ImpactValuePathSegmentDocument.Element &&
                    source.valuePath == target.valuePath
            ImpactReviewedCollectionSelectionDocument.CONTENT ->
                source.valuePath.values.isEmpty() && target.valuePath.values.isEmpty()
        }
    return path &&
        proof.matchesReceiver(source) &&
        admitsCallbackResult(proof) &&
        source.conservesFilterConditions(target, proof)
}

internal fun ImpactReviewedValueTransferDocument.admitsNonNullResult(): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    return source.role == ImpactValueRoleDocument.Return &&
        source.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(proof) &&
        admitsCallbackResult(proof) &&
        listOf(ImpactValuePathSegmentDocument.Element) + source.valuePath.values == target.valuePath.values &&
        source.conservesNonNullCondition(target, proof)
}

internal fun ImpactValueConditionDocument.ReviewedPredicateAccepts.admitsPredicateAcceptance(): Boolean =
    binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1 &&
        binding.admits(origin.execution.enclosing) &&
        binding.matchesReceiver(sourceSite()) &&
        useRange == binding.supplierRange &&
        (origin.valuePath.values.isEmpty() || origin.valuePath.values.first() == ImpactValuePathSegmentDocument.Element)

internal fun ImpactValueConditionDocument.ReviewedCallbackResultNonNull.admitsNonNullAcceptance(): Boolean =
    binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 &&
        binding.admits(origin.execution.enclosing) &&
        origin.role == ImpactValueRoleDocument.Return &&
        origin.execution.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(binding) &&
        binding.body.range.containsExecutionRange(origin.range) &&
        useRange == binding.supplierRange

private fun ImpactValueSiteReferenceDocument.conservesFilterConditions(
    target: ImpactValueSiteReferenceDocument,
    proof: ImpactReviewedValueBindingDocument,
): Boolean {
    if (
        target.conditions.values.size != conditions.values.size + FILTER_PREMISE_COUNT ||
            conditions.values != target.conditions.values.dropLast(FILTER_PREMISE_COUNT)
    )
        return false
    val normal =
        target.conditions.values[conditions.values.size] as? ImpactValueConditionDocument.ReviewedCollectionNormalResult
            ?: return false
    val accepted =
        target.conditions.values.last() as? ImpactValueConditionDocument.ReviewedPredicateAccepts ?: return false
    return normal.binding == proof &&
        normal.matchesSelectionOrigin(this, target.range) &&
        accepted.binding == proof &&
        accepted.matchesSelectionOrigin(this, target.range)
}

private fun ImpactValueSiteReferenceDocument.conservesNonNullCondition(
    target: ImpactValueSiteReferenceDocument,
    proof: ImpactReviewedValueBindingDocument,
): Boolean {
    if (
        target.conditions.values.size != conditions.values.size + 1 ||
            conditions.values != target.conditions.values.dropLast(1)
    )
        return false
    val accepted =
        target.conditions.values.last() as? ImpactValueConditionDocument.ReviewedCallbackResultNonNull ?: return false
    return accepted.binding == proof && accepted.matchesSelectionOrigin(this, target.range)
}

private fun ImpactValueConditionDocument.matchesSelectionOrigin(
    source: ImpactValueSiteReferenceDocument,
    result: ImpactSourceRangeDocument,
): Boolean = sourceSite().hasSameValueIdentity(source.copy(conditions = EmptyImpactConditions)) && useRange == result

private const val FILTER_PREMISE_COUNT = 2
