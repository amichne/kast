package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** The receiver anchor omits prior paths and conditions, so entry provenance remains bounded. */
class ReviewedInputEntryOrigin
private constructor(
    val family: ReviewedMapEntryFamily,
    val receiverRange: ExactDeclarationTextRange,
    val receiverExecution: ValueExecutionOwner,
    val selection: ReviewedEntrySelection,
) : ReviewedMapEntryOrigin {
    override val basis
        get() = family.basis

    override val retainedBytes: Long
        get() =
            family.retainedBytes
                .addBytes(valueExecutionStorageBytes(receiverExecution))
                .addBytes(REVIEWED_ENTRY_SELECTION_BYTES)

    companion object {
        internal fun fromContent(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedInputEntryOrigin, ValueFlowUnsupportedCause> {
            if (source.valuePath != ValueAccessPath.Whole)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return when (val family = ReviewedMapEntryFamily.fromReceiver(source, binding)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedInputEntryOrigin(
                            family.value,
                            source.range,
                            source.executionOwner,
                            ReviewedEntrySelection.CONTENT,
                        )
                    )
                is Refinement.Rejected -> family
            }
        }

        internal fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedInputEntryOrigin, ValueFlowUnsupportedCause> {
            if (source.valuePath.segments.firstOrNull() !is ValuePathSegment.MapEntryComponent)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return when (val family = ReviewedMapEntryFamily.fromReceiver(source, binding)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedInputEntryOrigin(
                            family.value,
                            source.range,
                            source.executionOwner,
                            source.reviewedEntrySelection(),
                        )
                    )
                is Refinement.Rejected -> family
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedInputEntryOrigin &&
            family == other.family &&
            receiverRange == other.receiverRange &&
            receiverExecution == other.receiverExecution &&
            selection == other.selection

    override fun hashCode(): Int = listOf(family, receiverRange, receiverExecution, selection).hashCode()
}

/** Key preservation is separate from the actual callback return witness. */
class ReviewedPreservedKeyOrigin
private constructor(
    val family: ReviewedMapEntryFamily,
    val receiverRange: ExactDeclarationTextRange,
    val receiverExecution: ValueExecutionOwner,
    val selection: ReviewedEntrySelection,
) : ReviewedMapEntryOrigin {
    override val basis
        get() = family.basis

    override val retainedBytes: Long
        get() =
            family.retainedBytes
                .addBytes(valueExecutionStorageBytes(receiverExecution))
                .addBytes(REVIEWED_ENTRY_SELECTION_BYTES)

    companion object {
        internal fun fromContent(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedPreservedKeyOrigin, ValueFlowUnsupportedCause> {
            if (source.valuePath != ValueAccessPath.Whole)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return when (val family = ReviewedMapEntryFamily.fromReceiver(source, binding)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedPreservedKeyOrigin(
                            family.value,
                            source.range,
                            source.executionOwner,
                            ReviewedEntrySelection.CONTENT,
                        )
                    )
                is Refinement.Rejected -> family
            }
        }

        internal fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedPreservedKeyOrigin, ValueFlowUnsupportedCause> {
            val component =
                source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent
                    ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            if (component.slot != MapEntrySlot.KEY)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return when (val family = ReviewedMapEntryFamily.fromReceiver(source, binding)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedPreservedKeyOrigin(
                            family.value,
                            source.range,
                            source.executionOwner,
                            source.reviewedEntrySelection(),
                        )
                    )
                is Refinement.Rejected -> family
            }
        }
    }

    internal fun matches(source: ValueSite): Boolean =
        source.range == receiverRange &&
            source.executionOwner == receiverExecution &&
            source.callStack == family.callStack &&
            admitMapEntryReceiver(source, family.binding) is Refinement.Refined &&
            matchesSelection(source)

    private fun matchesSelection(source: ValueSite): Boolean =
        if (source.valuePath == ValueAccessPath.Whole) selection == ReviewedEntrySelection.CONTENT
        else
            (source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                MapEntrySlot.KEY && selection == source.reviewedEntrySelection()

    override fun equals(other: Any?): Boolean =
        other is ReviewedPreservedKeyOrigin &&
            family == other.family &&
            receiverRange == other.receiverRange &&
            receiverExecution == other.receiverExecution &&
            selection == other.selection

    override fun hashCode(): Int = listOf(family, receiverRange, receiverExecution, selection).hashCode()
}
