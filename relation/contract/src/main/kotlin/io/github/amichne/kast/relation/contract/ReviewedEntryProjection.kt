package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun admitReviewedEntryProjection(
    source: ValueSite,
    binding: ReviewedCallValueBinding,
): Refinement<ValuePathSegment.MapEntryComponent, ValueFlowUnsupportedCause> {
    val component =
        source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    val receiver =
        source.role as? ValueRole.Receiver ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    if (source.basis != binding.basis || source.executionOwner != binding.invocation.executionOwner)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (receiver.call != binding.invocation || receiver.kind != binding.selected.operation.entryReceiverKind())
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    val slot =
        when (binding.selected.operation) {
            ReviewedLibraryOperation.MAP_ENTRY_KEY,
            ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1 -> MapEntrySlot.KEY
            ReviewedLibraryOperation.MAP_ENTRY_VALUE,
            ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 -> MapEntrySlot.VALUE
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
        }
    return if (component.slot == slot) Refinement.Refined(component)
    else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
}

private fun ReviewedLibraryOperation.entryReceiverKind(): ValueReceiverKind =
    when (this) {
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 -> ValueReceiverKind.EXTENSION
        else -> ValueReceiverKind.DISPATCH
    }

/** Preparation admits facts only; the existing transfer owner assembles the resulting value site. */
internal class ReviewedEntryResult
private constructor(
    override val relation: ReviewedValueRelation,
    override val path: ValueAccessPath,
    override val conditions: ValueConditions,
) : ReviewedResult {
    companion object {
        fun project(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedEntryResult, ValueFlowUnsupportedCause> {
            val component =
                when (val value = admitReviewedEntryProjection(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val path =
                when (val value = ValueAccessPath.fromSegments(source.valuePath.segments.drop(1))) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            val premise =
                when (val value = ValueCondition.ReviewedEntryComponentStable.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
                }
            return when (val conditions = entryProjectionConditions(source, binding, premise)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedEntryResult(
                            ReviewedValueRelation.EntryProjection(component.entry, component.slot),
                            path,
                            conditions.value,
                        )
                    )
                is Refinement.Rejected -> conditions
            }
        }

        fun preserveKey(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedEntryResult, ValueFlowUnsupportedCause> {
            val entry =
                when (val value = ReviewedPreservedKeyOrigin.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return preserveKey(source, entry)
        }

        fun preserveKeyContent(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedEntryResult, ValueFlowUnsupportedCause> =
            when (val value = ReviewedPreservedKeyOrigin.fromContent(source, binding)) {
                is Refinement.Refined -> preserveKey(source, value.value)
                is Refinement.Rejected -> value
            }

        private fun preserveKey(
            source: ValueSite,
            entry: ReviewedPreservedKeyOrigin,
        ): Refinement<ReviewedEntryResult, ValueFlowUnsupportedCause> {
            val path =
                when (
                    val value =
                        ValueAccessPath.fromSegments(
                            listOf(ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.KEY)) +
                                source.valuePath.segments.drop(1)
                        )
                ) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            val premise =
                when (val value = ValueCondition.ReviewedPreservedKeySurvives.fromReviewed(source, entry)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                }
            return conditioned(source, ReviewedValueRelation.PreservedKey(entry), path, premise)
        }

        private fun conditioned(
            source: ValueSite,
            relation: ReviewedValueRelation,
            path: ValueAccessPath,
            premise: ValueCondition,
        ): Refinement<ReviewedEntryResult, ValueFlowUnsupportedCause> =
            when (val value = appendReviewedCondition(source, premise)) {
                is Refinement.Refined -> Refinement.Refined(ReviewedEntryResult(relation, path, value.value))
                is Refinement.Rejected -> value
            }
    }
}

private fun entryProjectionConditions(
    source: ValueSite,
    binding: ReviewedCallValueBinding,
    stable: ValueCondition.ReviewedEntryComponentStable,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    val conditions =
        when (val value = appendReviewedCondition(source, stable)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val order = binding.entryReadOrder
    if (order !is ReviewedEntryReadOrder.Destructured || order.prefix == ReviewedEntryReadPrefix.Empty)
        return Refinement.Refined(conditions)
    val normal =
        when (val value = ValueCondition.ReviewedEntryPrefixNormal.fromReviewed(source, binding)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
        }
    if (normal in conditions.facts) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    return when (val value = conditions.requiring(normal)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    }
}
