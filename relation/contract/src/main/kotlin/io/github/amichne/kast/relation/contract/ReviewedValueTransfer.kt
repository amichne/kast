package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** The rule describes every returned callback value. Actual execution and invocation counts remain unknown. */
enum class ReviewedValueSemantics {
    CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER,
    CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
    CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
    CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT,
    CONDITIONAL_RECEIVER_TO_CALL_RESULT,
}

/** Reviewed correspondence is never a compiler transfer or a callback invocation fact. */
class ReviewedValueTransfer
private constructor(
    val source: ValueSite,
    val target: ValueSite,
    val binding: ReviewedCallbackValueBinding,
    val semantics: ReviewedValueSemantics,
) {
    val retainedBytes: Long
        get() = 8192L.addBytes(source.retainedBytes).addBytes(target.retainedBytes).addBytes(binding.retainedBytes)

    internal fun shareCallableEvidence(proofs: ValueFlowSiteSharing): ReviewedValueTransfer =
        ReviewedValueTransfer(proofs.site(source), proofs.site(target), binding, semantics)

    companion object {
        /** Input correspondence reaches a formal; actual body references establish later returns. */
        fun callbackInput(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
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
            return when (
                val target =
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
                is Refinement.Refined ->
                    Refinement.Refined(ReviewedValueTransfer(source, target.value, binding, semantics))
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            }
        }

        /** Whole receiver identity survives normal completion; callback writes do not establish component contents. */
        fun receiverResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            declared: DeclaredCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val composed =
                when (val result = binding.composeDeclared(declared)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val conditions =
                when (val result = reviewedReceiverConditions(source, composed)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            return when (
                val target =
                    ValueSite.fromCompiler(
                        enclosing = source.enclosing,
                        range = binding.occurrence.range,
                        role = ValueRole.ExpressionResult,
                        executionOwner = source.executionOwner,
                        valuePath = source.valuePath,
                        callStack = source.callStack,
                        conditions = conditions,
                    )
            ) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedValueTransfer(
                            source,
                            target.value,
                            composed,
                            ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT,
                        )
                    )
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        }

        fun callbackResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val admitted =
                when (val result = callbackBinding(source, binding)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            if (source.basis != binding.basis || source.role != ValueRole.Return)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            val path =
                when (
                    val result =
                        when (binding.selected.operation) {
                            ReviewedLibraryOperation.ITERABLE_MAP -> source.valuePath.prepend(ValuePathSegment.Element)
                            ReviewedLibraryOperation.LET,
                            ReviewedLibraryOperation.ALSO -> Refinement.Refined(source.valuePath)
                        }
                ) {
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
                        conditions = source.conditions,
                    )
            ) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedValueTransfer(source, target.value, admitted, admitted.outputSemantics())
                    )
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedValueTransfer &&
            source == other.source &&
            target == other.target &&
            binding == other.binding &&
            semantics == other.semantics

    override fun hashCode(): Int =
        31 * (31 * (31 * source.hashCode() + target.hashCode()) + binding.hashCode()) + semantics.hashCode()
}

private fun ValueSite.matchesReviewedReceiver(binding: ReviewedCallbackValueBinding): Boolean {
    if (
        valuePath != ValueAccessPath.Whole ||
            basis != binding.basis ||
            enclosing.valueIdentity != binding.enclosing.valueIdentity
    )
        return false
    return executionOwner.context == ValueExecutionContext.Declaration &&
        range.endExclusive <= binding.body.range.startInclusive
}

private fun ValueRole.Receiver.matchesReviewedCall(binding: ReviewedCallbackValueBinding): Boolean =
    kind == ValueReceiverKind.EXTENSION && call.range == binding.occurrence.range && call.matchesReviewedTarget(binding)

private fun ValueInvocation.matchesReviewedTarget(binding: ReviewedCallbackValueBinding): Boolean =
    callable.valueIdentity == ValueDeclarationIdentity.fromCompiler(binding.target) &&
        signature == binding.target.signature

private fun callbackBinding(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<ReviewedCallbackValueBinding, ValueFlowUnsupportedCause> =
    when (val owner = source.executionOwner.context) {
        is ValueExecutionContext.ReviewedCallback ->
            if (owner.binding == binding && binding.selected.operation == ReviewedLibraryOperation.ITERABLE_MAP)
                Refinement.Refined(binding)
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        is ValueExecutionContext.DeclaredCallback ->
            if (binding.selected.operation == ReviewedLibraryOperation.LET) binding.composeDeclared(owner.binding)
            else Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        ValueExecutionContext.Declaration,
        is ValueExecutionContext.Callback -> Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    }

internal fun ReviewedCallbackValueBinding.admitsReceiver(source: ValueSite): Boolean =
    when (val role = source.role) {
        is ValueRole.Receiver ->
            selected.operation == ReviewedLibraryOperation.ALSO &&
                source.matchesReviewedReceiver(this) &&
                role.matchesReviewedCall(this)
        else -> false
    }

private fun reviewedReceiverConditions(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    val condition =
        when (val admitted = ValueCondition.ReviewedCallbackNormalReturn.fromReviewed(source, binding)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        }
    if (condition in source.conditions.facts) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    return when (val admitted = source.conditions.requiring(condition)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }
}

private fun ReviewedCallbackValueBinding.outputSemantics(): ReviewedValueSemantics =
    when (selected.operation) {
        ReviewedLibraryOperation.ITERABLE_MAP -> ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
        ReviewedLibraryOperation.LET -> ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT
        ReviewedLibraryOperation.ALSO -> ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT
    }

private fun admitReviewedInput(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    formal: ValueFormalParameter,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val receiver =
        source.role as? ValueRole.Receiver
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (
        binding.selected.operation != ReviewedLibraryOperation.ITERABLE_MAP ||
            !receiver.matchesReviewedCall(binding) ||
            source.range.endExclusive > binding.body.range.startInclusive
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (
        source.basis != binding.basis ||
            source.enclosing.valueIdentity != binding.enclosing.valueIdentity ||
            source.executionOwner.context != ValueExecutionContext.Declaration
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (formal.executionOwner != ValueExecutionOwner.reviewedCallback(binding))
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (formal.position.value != 0) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    return Refinement.Refined(Unit)
}
