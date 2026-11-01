package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** One static callback context never identifies an individual dynamic entry or iteration. */
class ReviewedMapEntryFamily
private constructor(
    val binding: ReviewedCallbackValueBinding,
    val callStack: ValueCallStack,
) {
    val basis
        get() = binding.basis

    val retainedBytes: Long
        get() =
            binding.retainedBytes.addBytes(
                callStack.frames.fold(0L) { sum, frame -> sum.addBytes(valueFrameStorageBytes(frame)) }
            )

    companion object {
        internal fun fromReturn(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedMapEntryFamily, ValueFlowUnsupportedCause> {
            if (binding.selected.operation != ReviewedLibraryOperation.MAP_VALUES || source.role != ValueRole.Return)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            if (source.basis != binding.basis || source.executionOwner != ValueExecutionOwner.reviewedCallback(binding))
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            return Refinement.Refined(ReviewedMapEntryFamily(binding, source.callStack))
        }

        internal fun fromReceiver(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedMapEntryFamily, ValueFlowUnsupportedCause> =
            when (val admitted = admitMapEntryReceiver(source, binding)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined -> Refinement.Refined(ReviewedMapEntryFamily(binding, source.callStack))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedMapEntryFamily && binding == other.binding && callStack == other.callStack

    override fun hashCode(): Int = 31 * binding.hashCode() + callStack.hashCode()
}

internal fun admitMapEntryReceiver(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val receiver =
        source.role as? ValueRole.Receiver
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (
        binding.selected.operation != ReviewedLibraryOperation.MAP_VALUES ||
            receiver.kind != ValueReceiverKind.EXTENSION
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (
        receiver.call.range != binding.occurrence.range ||
            receiver.call.signature != binding.target.signature ||
            receiver.call.callable.valueIdentity != ValueDeclarationIdentity.fromCompiler(binding.target)
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (source.basis != binding.basis || source.executionOwner != binding.parentExecutionOwner)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (
        !binding.occurrence.range.containsValueRange(source.range) ||
            source.range.endExclusive > binding.body.range.startInclusive
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    return Refinement.Refined(Unit)
}
