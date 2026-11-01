package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueSite
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/** Whole-map input has separate key and value content branches, never an entry identity. */
internal fun nativeReviewedMapInputs(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
    retained: NativeValueFlowTransfers,
) {
    when (val parameter = nativeReviewedInputParameter(binding, literal, context)) {
        is Refinement.Refined ->
            mapInputs(
                source = source,
                binding = binding,
                formal = parameter.value,
                retained = retained,
            )
        is Refinement.Rejected -> retained.unresolved(parameter.failure)
    }
    if (source.valuePath == ValueAccessPath.Whole)
        retained.adoptReviewed(ReviewedValueTransfer.mapPreservedKeyContent(source, binding))
    else {
        val component = source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent
        if (component?.slot == MapEntrySlot.KEY)
            retained.adoptReviewed(ReviewedValueTransfer.mapPreservedKey(source, binding))
    }
}

private fun mapInputs(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    formal: ValueFormalParameter,
    retained: NativeValueFlowTransfers,
) {
    if (source.valuePath != ValueAccessPath.Whole) {
        retained.adoptReviewed(ReviewedValueTransfer.callbackInput(source, binding, formal))
        return
    }
    for (slot in MapEntrySlot.entries) retained.adoptReviewed(
        ReviewedValueTransfer.entryContentInput(source, binding, formal, slot)
    )
}
