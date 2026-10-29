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
    val binding: ReviewedValueAuthority,
    val relation: ReviewedValueRelation,
) {
    val retainedBytes: Long
        get() = 8192L.addBytes(source.retainedBytes).addBytes(target.retainedBytes).addBytes(binding.retainedBytes)

    internal fun shareCallableEvidence(proofs: ValueFlowSiteSharing): ReviewedValueTransfer =
        ReviewedValueTransfer(proofs.site(source), proofs.site(target), binding, relation)

    companion object {
        fun sortedResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val premise =
                when (val value = ValueCondition.ReviewedCollectionNormalResult.fromReviewed(source, binding)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                }
            val conditions =
                when (val value = appendReviewedCondition(source, premise)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val selection =
                if (source.valuePath == ValueAccessPath.Whole) ReviewedCollectionSelection.CONTENT
                else ReviewedCollectionSelection.ELEMENT
            return finishReviewed(
                source,
                binding,
                ReviewedValueRelation.ReorderedElements(selection),
                source.valuePath,
                conditions,
            )
        }

        fun tupleConstruct(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
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
            return finishReviewed(
                source,
                binding,
                ReviewedValueRelation.TupleConstruction(origin, input, slot),
                path,
                source.conditions,
            )
        }

        fun tupleProject(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
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
            return finishReviewed(
                source,
                binding,
                ReviewedValueRelation.TupleProjection(component.origin, component.slot),
                path,
                source.conditions,
            )
        }

        fun associatedReturn(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            associatedReturnWithEvidence(source, binding, ReviewedEntryKeyEvidence.Conditional)

        fun associatedReturn(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            proof: ReviewedDistinctStringKeys,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            associatedReturnWithEvidence(source, binding, ReviewedEntryKeyEvidence.Distinct(proof))

        private fun associatedReturnWithEvidence(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            evidence: ReviewedEntryKeyEvidence,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val entry =
                when (val value = ReviewedMapEntryCorrelation.fromReviewed(source, binding, evidence)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val component = source.valuePath.segments.first() as ValuePathSegment.PairComponent
            val slot =
                when (component.slot) {
                    PairSlot.FIRST -> MapEntrySlot.KEY
                    PairSlot.SECOND -> MapEntrySlot.VALUE
                }
            val path =
                when (val value = reviewedAssociationPath(source, entry, slot)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val conditions =
                when (val value = reviewedAssociationConditions(source, entry)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return finishReviewed(
                source,
                binding,
                ReviewedValueRelation.AssociationComponent(entry, slot),
                path,
                conditions,
            )
        }

        private fun finishReviewed(
            source: ValueSite,
            binding: ReviewedValueAuthority,
            relation: ReviewedValueRelation,
            path: ValueAccessPath,
            conditions: ValueConditions,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val execution =
                when (binding) {
                    is ReviewedCallbackValueBinding -> ValueExecutionOwner.declaration(binding.enclosing)
                    is ReviewedCallValueBinding -> binding.invocation.executionOwner
                }
            return when (
                val target =
                    ValueSite.fromCompiler(
                        enclosing = binding.enclosing,
                        range = binding.occurrence.range,
                        role = ValueRole.ExpressionResult,
                        executionOwner = execution,
                        valuePath = path,
                        callStack = source.callStack,
                        conditions = conditions,
                    )
            ) {
                is Refinement.Refined ->
                    Refinement.Refined(ReviewedValueTransfer(source, target.value, binding, relation))
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        }

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
                    Refinement.Refined(
                        ReviewedValueTransfer(
                            source,
                            target.value,
                            binding,
                            ReviewedValueRelation.CallbackCorrespondence(semantics),
                        )
                    )
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
                            ReviewedValueRelation.CallbackCorrespondence(
                                ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT
                            ),
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
            val semantics =
                when (val value = admitted.outputSemantics()) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val path =
                when (val result = reviewedCallbackOutputPath(source, binding)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            return finishReviewed(
                source,
                admitted,
                ReviewedValueRelation.CallbackCorrespondence(semantics),
                path,
                source.conditions,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedValueTransfer &&
            source == other.source &&
            target == other.target &&
            binding == other.binding &&
            relation == other.relation

    override fun hashCode(): Int =
        31 * (31 * (31 * source.hashCode() + target.hashCode()) + binding.hashCode()) + relation.hashCode()
}
