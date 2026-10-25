package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** The rule describes every returned callback value. Actual execution and invocation counts remain unknown. */
enum class ReviewedValueSemantics {
    CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
}

/** Reviewed correspondence is never a compiler transfer or a callback invocation fact. */
class ReviewedValueTransfer
private constructor(
    val source: ValueSite,
    val target: ValueSite,
    val binding: ReviewedCallbackValueBinding,
) {
    val semantics = ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
    val retainedBytes: Long
        get() = 8192L.addBytes(source.retainedBytes).addBytes(target.retainedBytes)

    internal fun shareCallableEvidence(proofs: ValueFlowSiteSharing): ReviewedValueTransfer =
        ReviewedValueTransfer(proofs.site(source), proofs.site(target), binding)

    companion object {
        fun callbackResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val owner =
                source.executionOwner.context as? ValueExecutionContext.ReviewedCallback
                    ?: return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (source.basis != binding.basis || owner.binding != binding || source.role != ValueRole.Return)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            val path =
                when (val result = source.valuePath.prepend(ValuePathSegment.Element)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
                }
            return when (
                val target =
                    ValueSite.fromCompiler(
                        enclosing = binding.enclosing,
                        range = binding.occurrence.range,
                        role = ValueRole.ExpressionResult,
                        executionOwner = ValueExecutionOwner.declaration(binding.enclosing),
                        valuePath = path,
                        callStack = source.callStack,
                    )
            ) {
                is Refinement.Refined -> Refinement.Refined(ReviewedValueTransfer(source, target.value, binding))
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedValueTransfer && source == other.source && target == other.target && binding == other.binding

    override fun hashCode(): Int = 31 * (31 * source.hashCode() + target.hashCode()) + binding.hashCode()
}
