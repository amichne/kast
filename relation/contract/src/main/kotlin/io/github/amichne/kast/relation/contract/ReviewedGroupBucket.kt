package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

enum class ReviewedGroupPlacement {
    ORIGINAL_ELEMENTS,
    TRANSFORMED_ELEMENTS,
    KEY_CONTENT,
}

/** A bucket identifies a static qualified family, without cardinality or runtime iteration identity. */
class ReviewedGroupBucket
private constructor(
    val callbacks: ReviewedGroupCallbacks,
    val placement: ReviewedGroupPlacement,
    val selection: ReviewedEntrySelection,
    val sourceRange: ExactDeclarationTextRange,
    val sourceExecution: ValueExecutionOwner,
    val callStack: ValueCallStack,
) : ReviewedMapEntryOrigin {
    val slot: MapEntrySlot
        get() = if (placement == ReviewedGroupPlacement.KEY_CONTENT) MapEntrySlot.KEY else MapEntrySlot.VALUE

    override val basis
        get() = callbacks.selector.basis

    override val retainedBytes: Long
        get() =
            callbacks.retainedBytes
                .addBytes(valueExecutionStorageBytes(sourceExecution))
                .addBytes(callStack.frames.fold(0L) { sum, frame -> sum.addBytes(valueFrameStorageBytes(frame)) })
                .addBytes(GROUP_POSITION_BYTES)

    internal fun matches(source: ValueSite): Boolean =
        source.range == sourceRange &&
            source.executionOwner == sourceExecution &&
            source.callStack == callStack &&
            source.basis == basis &&
            selection == source.groupSelection(placement) &&
            admitsSource(source, callbacks, placement)

    companion object {
        internal fun fromReviewed(
            source: ValueSite,
            callbacks: ReviewedGroupCallbacks,
            placement: ReviewedGroupPlacement,
        ): Refinement<ReviewedGroupBucket, ValueFlowUnsupportedCause> {
            if (!admitsSource(source, callbacks, placement))
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            val selection = source.groupSelection(placement)
            return Refinement.Refined(
                ReviewedGroupBucket(
                    callbacks,
                    placement,
                    selection,
                    source.range,
                    source.executionOwner,
                    source.callStack,
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedGroupBucket &&
            callbacks == other.callbacks &&
            placement == other.placement &&
            selection == other.selection &&
            sourceRange == other.sourceRange &&
            sourceExecution == other.sourceExecution &&
            callStack == other.callStack

    override fun hashCode(): Int =
        listOf(callbacks, placement, selection, sourceRange, sourceExecution, callStack).hashCode()
}

private fun admitsSource(
    source: ValueSite,
    callbacks: ReviewedGroupCallbacks,
    placement: ReviewedGroupPlacement,
): Boolean =
    when (placement) {
        ReviewedGroupPlacement.ORIGINAL_ELEMENTS ->
            callbacks is ReviewedGroupCallbacks.OriginalElements && callbacks.selector.admitsSelectedReceiver(source)
        ReviewedGroupPlacement.TRANSFORMED_ELEMENTS ->
            callbacks is ReviewedGroupCallbacks.TransformedElements && source.matchesGroupReturn(callbacks.transform)
        ReviewedGroupPlacement.KEY_CONTENT -> source.matchesGroupReturn(callbacks.selector)
    }

private fun ValueSite.matchesGroupReturn(binding: ReviewedCallbackValueBinding): Boolean =
    role == ValueRole.Return &&
        basis == binding.basis &&
        executionOwner == ValueExecutionOwner.reviewedCallback(binding)

private const val GROUP_POSITION_BYTES = 32L

private fun ValueSite.groupSelection(placement: ReviewedGroupPlacement): ReviewedEntrySelection =
    if (
        placement == ReviewedGroupPlacement.KEY_CONTENT ||
            placement == ReviewedGroupPlacement.ORIGINAL_ELEMENTS && valuePath == ValueAccessPath.Whole
    )
        ReviewedEntrySelection.CONTENT
    else reviewedEntrySelection()
