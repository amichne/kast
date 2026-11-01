package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal class ReviewedInputCorrespondence
private constructor(
    val path: ValueAccessPath,
    val relation: ReviewedValueRelation,
) {
    fun target(source: ValueSite, formal: ValueFormalParameter): Refinement<ValueSite, ValueFlowUnsupportedCause> =
        when (
            val value =
                ValueSite.fromCompiler(
                    enclosing = formal.callable,
                    range = formal.range,
                    role = ValueRole.Formal(ValueFormalPosition.Parameter(formal)),
                    executionOwner = formal.executionOwner,
                    valuePath = path,
                    callStack = source.callStack,
                    conditions = source.conditions,
                )
        ) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }

    companion object {
        fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
        ): Refinement<ReviewedInputCorrespondence, ValueFlowUnsupportedCause> {
            if (binding.selected.operation == ReviewedLibraryOperation.MAP_VALUES)
                return mapEntry(source, binding, formal)
            when (val admitted = admitReviewedInput(source, binding, formal)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            val path =
                when (val selected = source.valuePath.select(ValuePathSegment.Element)) {
                    is ValuePathSelection.Selected -> selected.path
                    ValuePathSelection.DifferentField ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                }
            val semantics =
                if (source.valuePath == ValueAccessPath.Whole)
                    ReviewedValueSemantics.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER
                else ReviewedValueSemantics.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER
            return Refinement.Refined(
                ReviewedInputCorrespondence(path, ReviewedValueRelation.CallbackCorrespondence(semantics))
            )
        }

        private fun mapEntry(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
        ): Refinement<ReviewedInputCorrespondence, ValueFlowUnsupportedCause> {
            val entry =
                when (val value = ReviewedInputEntryOrigin.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val slot = (source.valuePath.segments.first() as ValuePathSegment.MapEntryComponent).slot
            return mapEntry(binding, formal, entry, slot, source.valuePath.segments.drop(1))
        }

        fun fromContent(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
            slot: MapEntrySlot,
        ): Refinement<ReviewedInputCorrespondence, ValueFlowUnsupportedCause> {
            val entry =
                when (val value = ReviewedInputEntryOrigin.fromContent(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return mapEntry(binding, formal, entry, slot, emptyList())
        }

        private fun mapEntry(
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
            entry: ReviewedInputEntryOrigin,
            slot: MapEntrySlot,
            suffix: List<ValuePathSegment>,
        ): Refinement<ReviewedInputCorrespondence, ValueFlowUnsupportedCause> {
            if (formal.executionOwner != ValueExecutionOwner.reviewedCallback(binding))
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (formal.position.value != 0) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            val path =
                when (
                    val value =
                        ValueAccessPath.fromSegments(listOf(ValuePathSegment.MapEntryComponent(entry, slot)) + suffix)
                ) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            return Refinement.Refined(ReviewedInputCorrespondence(path, ReviewedValueRelation.EntryInput(entry, slot)))
        }
    }
}
