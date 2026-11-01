package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** An entry origin identifies a static value family without a runtime iteration claim. */
sealed interface ReviewedMapEntryOrigin {
    val basis: SemanticReadIdentity
    val retainedBytes: Long
}

/** An actual mapValues return supplies a value, without proof of its input key or receiver identity. */
class ReviewedMappedEntryOrigin
private constructor(
    val family: ReviewedMapEntryFamily,
    val returnRange: ExactDeclarationTextRange,
    val returnExecution: ValueExecutionOwner,
    val selection: ReviewedEntrySelection,
) : ReviewedMapEntryOrigin {
    val binding
        get() = family.binding

    val callStack
        get() = family.callStack

    override val basis
        get() = family.basis

    override val retainedBytes: Long
        get() =
            binding.retainedBytes
                .addBytes(REVIEWED_ENTRY_SELECTION_BYTES)
                .addBytes(valueExecutionStorageBytes(returnExecution))
                .addBytes(callStack.frames.fold(0L) { sum, frame -> sum.addBytes(valueFrameStorageBytes(frame)) })

    companion object {
        internal fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedMappedEntryOrigin, ValueFlowUnsupportedCause> {
            val family =
                when (val value = ReviewedMapEntryFamily.fromReturn(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return Refinement.Refined(
                ReviewedMappedEntryOrigin(
                    family = family,
                    returnRange = source.range,
                    returnExecution = source.executionOwner,
                    selection = source.reviewedEntrySelection(),
                )
            )
        }
    }

    internal fun matches(source: ValueSite): Boolean =
        source.role == ValueRole.Return &&
            source.range == returnRange &&
            source.executionOwner == returnExecution &&
            source.callStack == callStack &&
            selection == source.reviewedEntrySelection()

    override fun equals(other: Any?): Boolean =
        other is ReviewedMappedEntryOrigin &&
            binding == other.binding &&
            returnRange == other.returnRange &&
            returnExecution == other.returnExecution &&
            callStack == other.callStack &&
            selection == other.selection

    override fun hashCode(): Int = listOf(binding, returnRange, returnExecution, callStack, selection).hashCode()
}

/** A prepared mapped result preserves its admitted entry, bounded path, and conditional facts. */
internal class ReviewedMappedResult
private constructor(
    val entry: ReviewedMappedEntryOrigin,
    val path: ValueAccessPath,
    val conditions: ValueConditions,
) {
    companion object {
        fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedMappedResult, ValueFlowUnsupportedCause> {
            val entry =
                when (val value = ReviewedMappedEntryOrigin.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val path =
                when (
                    val value = source.valuePath.prepend(ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.VALUE))
                ) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            val condition =
                when (val value = ValueCondition.ReviewedMappedKeySurvives.fromReviewed(source, entry)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                }
            return when (val value = appendReviewedCondition(source, condition)) {
                is Refinement.Refined -> Refinement.Refined(ReviewedMappedResult(entry, path, value.value))
                is Refinement.Rejected -> value
            }
        }
    }
}
