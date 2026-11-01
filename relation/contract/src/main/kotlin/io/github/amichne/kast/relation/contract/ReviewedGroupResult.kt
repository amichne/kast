package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal class ReviewedGroupResult
private constructor(
    override val relation: ReviewedValueRelation,
    override val path: ValueAccessPath,
    override val conditions: ValueConditions,
) : ReviewedResult {
    companion object {
        fun original(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedGroupResult, ValueFlowUnsupportedCause> =
            prepare(
                source,
                ReviewedGroupCallbacks.OriginalElements.fromReviewed(selector),
                ReviewedGroupPlacement.ORIGINAL_ELEMENTS,
            )

        fun transformed(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
            transform: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedGroupResult, ValueFlowUnsupportedCause> =
            prepare(
                source,
                ReviewedGroupCallbacks.TransformedElements.fromReviewed(selector, transform),
                ReviewedGroupPlacement.TRANSFORMED_ELEMENTS,
            )

        fun key(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedGroupResult, ValueFlowUnsupportedCause> =
            prepare(
                source,
                ReviewedGroupCallbacks.OriginalElements.fromReviewed(selector),
                ReviewedGroupPlacement.KEY_CONTENT,
            )

        fun key(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
            transform: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedGroupResult, ValueFlowUnsupportedCause> =
            prepare(
                source,
                ReviewedGroupCallbacks.TransformedElements.fromReviewed(selector, transform),
                ReviewedGroupPlacement.KEY_CONTENT,
            )

        private fun prepare(
            source: ValueSite,
            callbacks: Refinement<ReviewedGroupCallbacks, ValueFlowUnsupportedCause>,
            placement: ReviewedGroupPlacement,
        ): Refinement<ReviewedGroupResult, ValueFlowUnsupportedCause> {
            val family =
                when (callbacks) {
                    is Refinement.Refined -> callbacks.value
                    is Refinement.Rejected -> return callbacks
                }
            val entry =
                when (val value = ReviewedGroupBucket.fromReviewed(source, family, placement)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val path =
                when (val value = groupPath(source, entry)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val premise =
                when (val value = groupCondition(source, entry)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                }
            return when (val value = appendReviewedCondition(source, premise)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedGroupResult(ReviewedValueRelation.GroupPlacement(entry), path, value.value)
                    )
                is Refinement.Rejected -> value
            }
        }
    }
}

private fun groupCondition(
    source: ValueSite,
    entry: ReviewedGroupBucket,
): Refinement<ValueCondition, ValueSiteFailure> =
    if (entry.placement == ReviewedGroupPlacement.KEY_CONTENT)
        ReviewedGroupKeyRepresentative.fromReviewed(source, entry)
    else ReviewedGroupMembership.fromReviewed(source, entry)

private fun groupPath(
    source: ValueSite,
    entry: ReviewedGroupBucket,
): Refinement<ValueAccessPath, ValueFlowUnsupportedCause> {
    val suffix =
        if (entry.placement == ReviewedGroupPlacement.ORIGINAL_ELEMENTS) source.valuePath.segments.drop(1)
        else source.valuePath.segments
    val prefix =
        if (entry.slot == MapEntrySlot.VALUE)
            listOf(ValuePathSegment.MapEntryComponent(entry, entry.slot), ValuePathSegment.Element)
        else listOf(ValuePathSegment.MapEntryComponent(entry, entry.slot))
    return when (val value = ValueAccessPath.fromSegments(prefix + suffix)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
    }
}
