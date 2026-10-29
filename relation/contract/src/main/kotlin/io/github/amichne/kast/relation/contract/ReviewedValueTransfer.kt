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
        get() =
            8192L
                .addBytes(source.retainedBytes)
                .addBytes(target.retainedBytes)
                .addBytes(binding.retainedBytes)
                .addBytes(
                    if (relation is ReviewedValueRelation.CallbackReceiverInput) relation.receiver.retainedBytes else 0L
                )

    internal fun shareCallableEvidence(proofs: ValueFlowSiteSharing): ReviewedValueTransfer =
        ReviewedValueTransfer(proofs.site(source), proofs.site(target), binding, relation)

    companion object {
        fun sortedResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, binding, ReviewedSelectionResult.sorted(source, binding))

        fun filteredResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, binding, ReviewedSelectionResult.filtered(source, binding))

        fun tupleConstruct(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishTupleResult(source, binding, ReviewedTupleResult.construct(source, binding))

        fun tupleProject(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishTupleResult(source, binding, ReviewedTupleResult.project(source, binding))

        private fun finishTupleResult(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
            result: Refinement<ReviewedTupleResult, ValueFlowUnsupportedCause>,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            when (result) {
                is Refinement.Rejected -> result
                is Refinement.Refined ->
                    finishReviewed(source, binding, result.value.relation, result.value.path, source.conditions)
            }

        fun groupedReceiverElement(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, selector, ReviewedGroupResult.original(source, selector))

        fun groupedCallbackResult(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
            transform: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, transform, ReviewedGroupResult.transformed(source, selector, transform))

        fun groupedKeyResult(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, selector, ReviewedGroupResult.key(source, selector))

        fun groupedKeyResult(
            source: ValueSite,
            selector: ReviewedCallbackValueBinding,
            transform: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, selector, ReviewedGroupResult.key(source, selector, transform))

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
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, binding, ReviewedAssociationResult.fromReviewed(source, binding, evidence))

        private fun finishReviewed(
            source: ValueSite,
            binding: ReviewedValueAuthority,
            relation: ReviewedValueRelation,
            path: ValueAccessPath,
            conditions: ValueConditions,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val execution =
                when (binding) {
                    is ReviewedCallbackValueBinding -> binding.parentExecutionOwner
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

        fun callbackReceiverInput(
            source: ValueSite,
            receiver: ReviewedCallbackReceiver,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            when (val target = receiver.target(source)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedValueTransfer(
                            source,
                            target.value,
                            receiver.binding,
                            ReviewedValueRelation.CallbackReceiverInput(receiver),
                        )
                    )
                is Refinement.Rejected -> target
            }

        /** Input correspondence reaches a formal; actual body references establish later returns. */
        fun callbackInput(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishInput(source, binding, formal, ReviewedInputCorrespondence.fromReviewed(source, binding, formal))

        fun entryContentInput(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
            slot: MapEntrySlot,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishInput(source, binding, formal, ReviewedInputCorrespondence.fromContent(source, binding, formal, slot))

        private fun finishInput(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            formal: ValueFormalParameter,
            result: Refinement<ReviewedInputCorrespondence, ValueFlowUnsupportedCause>,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
            val correspondence =
                when (result) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            return when (val target = correspondence.target(source, formal)) {
                is Refinement.Refined ->
                    Refinement.Refined(ReviewedValueTransfer(source, target.value, binding, correspondence.relation))
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

        fun mapPreservedKey(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, binding, ReviewedEntryResult.preserveKey(source, binding))

        fun mapPreservedKeyContent(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, binding, ReviewedEntryResult.preserveKeyContent(source, binding))

        fun entryProject(
            source: ValueSite,
            binding: ReviewedCallValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            finishResult(source, binding, ReviewedEntryResult.project(source, binding))

        private fun finishResult(
            source: ValueSite,
            binding: ReviewedValueAuthority,
            result: Refinement<ReviewedResult, ValueFlowUnsupportedCause>,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            when (result) {
                is Refinement.Rejected -> result
                is Refinement.Refined ->
                    finishReviewed(
                        source,
                        binding,
                        result.value.relation,
                        result.value.path,
                        result.value.conditions,
                    )
            }

        private fun mappedResult(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
            when (val result = ReviewedMappedResult.fromReviewed(source, binding)) {
                is Refinement.Rejected -> result
                is Refinement.Refined ->
                    finishReviewed(
                        source,
                        binding,
                        ReviewedValueRelation.MappedValue(result.value.entry),
                        result.value.path,
                        result.value.conditions,
                    )
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
            if (admitted.selected.operation == ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL)
                return finishResult(source, admitted, ReviewedSelectionResult.nonNull(source, admitted))
            if (admitted.selected.operation == ReviewedLibraryOperation.MAP_VALUES)
                return mappedResult(source, admitted)
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
