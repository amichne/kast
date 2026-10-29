package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Preparation preserves membership premises independently from callback invocation authority. */
internal class ReviewedSelectionResult
private constructor(
    override val relation: ReviewedValueRelation,
    override val path: ValueAccessPath,
    override val conditions: ValueConditions,
) : ReviewedResult {
    companion object {
        fun sorted(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedSelectionResult, ValueFlowUnsupportedCause> {
            if (!binding.admitsSortedReceiver(source))
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            return when (val conditions = normalConditions(source, binding)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedSelectionResult(
                            ReviewedValueRelation.ReorderedElements(source.collectionSelection()),
                            source.valuePath,
                            conditions.value,
                        )
                    )
                is Refinement.Rejected -> conditions
            }
        }

        fun filtered(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedSelectionResult, ValueFlowUnsupportedCause> {
            val membership =
                when (val value = ReviewedPredicateAccepts.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                }
            val conditions =
                when (val value = normalConditions(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            if (membership in conditions.facts) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            return when (val value = conditions.requiring(membership)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedSelectionResult(
                            ReviewedValueRelation.FilteredElements(source.collectionSelection()),
                            source.valuePath,
                            value.value,
                        )
                    )
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        }

        fun nonNull(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedSelectionResult, ValueFlowUnsupportedCause> {
            val premise =
                when (val value = ReviewedCallbackResultNonNull.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                }
            val path =
                when (val value = source.valuePath.prepend(ValuePathSegment.Element)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            return when (val conditions = appendReviewedCondition(source, premise)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedSelectionResult(ReviewedValueRelation.NonNullCallbackResult, path, conditions.value)
                    )
                is Refinement.Rejected -> conditions
            }
        }
    }
}

private fun ValueSite.collectionSelection(): ReviewedCollectionSelection =
    if (valuePath == ValueAccessPath.Whole) ReviewedCollectionSelection.CONTENT else ReviewedCollectionSelection.ELEMENT

private fun normalConditions(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    val normal =
        when (val value = ValueCondition.ReviewedCollectionNormalResult.fromReviewed(source, binding)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        }
    return appendReviewedCondition(source, normal)
}
