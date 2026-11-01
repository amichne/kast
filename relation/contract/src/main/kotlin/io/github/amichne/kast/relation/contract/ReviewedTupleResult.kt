package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Tuple preparation preserves the admitted position and path before the transfer owner assembles its target. */
internal class ReviewedTupleResult
private constructor(
    val relation: ReviewedValueRelation,
    val path: ValueAccessPath,
) {
    companion object {
        fun construct(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedTupleResult, ValueFlowUnsupportedCause> {
            val input =
                when (val value = reviewedTupleInput(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val slot =
                when (binding.selected.operation) {
                    ReviewedLibraryOperation.TO ->
                        if (input == ReviewedTupleInput.EXTENSION_RECEIVER) PairSlot.FIRST else PairSlot.SECOND
                    else -> if (input == ReviewedTupleInput.ARGUMENT_ZERO) PairSlot.FIRST else PairSlot.SECOND
                }
            val origin =
                when (val value = ReviewedTupleOrigin.fromReviewed(binding, source.callStack)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val path =
                when (val value = source.valuePath.prepend(ValuePathSegment.PairComponent(origin, slot))) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            return Refinement.Refined(
                ReviewedTupleResult(ReviewedValueRelation.TupleConstruction(origin, input, slot), path)
            )
        }

        fun project(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedTupleResult, ValueFlowUnsupportedCause> {
            val component =
                source.valuePath.segments.firstOrNull() as? ValuePathSegment.PairComponent
                    ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
            when (val value = admitTupleProjection(source, binding, component)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return value
            }
            val path =
                when (val value = source.valuePath.select(component)) {
                    is ValuePathSelection.Selected -> value.path
                    ValuePathSelection.DifferentField ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
                }
            return Refinement.Refined(
                ReviewedTupleResult(ReviewedValueRelation.TupleProjection(component.origin, component.slot), path)
            )
        }
    }
}
