package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal class ReviewedAssociationResult
private constructor(
    override val relation: ReviewedValueRelation,
    override val path: ValueAccessPath,
    override val conditions: ValueConditions,
) : ReviewedResult {
    companion object {
        fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            evidence: ReviewedEntryKeyEvidence,
        ): Refinement<ReviewedAssociationResult, ValueFlowUnsupportedCause> {
            val entry =
                when (val value = ReviewedMapEntryCorrelation.fromReviewed(source, binding, evidence)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val component = source.valuePath.segments.first() as ValuePathSegment.PairComponent
            val slot =
                when (component.slot) {
                    PairSlot.FIRST -> MapEntrySlot.KEY
                    PairSlot.SECOND -> MapEntrySlot.VALUE
                }
            val path =
                when (val value = reviewedAssociationPath(source, entry, slot)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val conditions =
                when (val value = reviewedAssociationConditions(source, entry)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return Refinement.Refined(
                ReviewedAssociationResult(ReviewedValueRelation.AssociationComponent(entry, slot), path, conditions)
            )
        }
    }
}
