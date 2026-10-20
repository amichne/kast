package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class ValueModelApplicationFailure {
    SOURCE_MISMATCH,
    BASIS_MISMATCH,
    RECEIVER_KIND_MISMATCH,
    RULE_MISMATCH,
    PATH_UNSELECTED,
    ACCESS_PATH_DEPTH_EXCEEDED,
}

/** A reviewed condition grants no compiler-established non-null or nonempty fact. */
data class SelectedElementCondition
internal constructor(
    val receiver: ValueSite,
    val result: ValueSite,
    val index: ValueElementIndex,
) {
    companion object {
        internal fun fromSelection(
            source: ValueSite,
            target: ValueSite,
            index: ValueElementIndex,
        ): Refinement<SelectedElementCondition, ValueModelApplicationFailure> {
            val receiver =
                when (val whole = source.withState(valuePath = ValueAccessPath.Whole)) {
                    is Refinement.Refined -> whole.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
                }
            val result =
                when (val whole = target.withState(valuePath = ValueAccessPath.Whole)) {
                    is Refinement.Refined -> whole.value
                    is Refinement.Rejected -> return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
                }
            return Refinement.Refined(SelectedElementCondition(receiver, result, index))
        }
    }
}

/** Reviewed applications carry exact calls and conditional selections separately from native transfers. */
sealed interface ValueModelApplication : RepresentationHistory.Model {
    val source: ValueSite
    val target: ValueSite
    val binding: ReviewedLibraryBinding
    override val reference: ModelRuleReference
        get() = binding.model.reference

    override val site: ValueSiteIdentity
        get() = target.identity

    val invocation: ValueInvocation
        get() = binding.invocation

    @ConsistentCopyVisibility
    data class CollectionInsertion
    private constructor(
        override val source: ValueSite,
        override val target: ValueSite,
        override val binding: ReviewedLibraryBinding,
        val index: ValueElementIndex,
    ) : ValueModelApplication {
        companion object {
            fun insertSingle(
                source: ValueSite,
                binding: ReviewedLibraryBinding,
            ): Refinement<CollectionInsertion, ValueModelApplicationFailure> {
                if (binding.model != ReviewedValueModel.SingleElementListOf)
                    return Refinement.Rejected(ValueModelApplicationFailure.RULE_MISMATCH)
                if (source.basis != binding.invocation.basis)
                    return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
                val role =
                    source.role as? ValueRole.Argument
                        ?: return Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)
                if (!matchesSource(source, role, binding))
                    return Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)
                val index = (ValueElementIndex.parse(0) as Refinement.Refined).value
                val path =
                    when (val result = source.valuePath.prepend(ValuePathSegment.Element(index))) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(ValueModelApplicationFailure.ACCESS_PATH_DEPTH_EXCEEDED)
                    }
                val target =
                    when (
                        val result =
                            binding.invocation.resultSite().withState(path, source.callStack, source.predicates)
                    ) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
                    }
                return Refinement.Refined(
                    CollectionInsertion(source = source, target = target, binding = binding, index = index)
                )
            }

            private fun matchesSource(
                source: ValueSite,
                role: ValueRole.Argument,
                binding: ReviewedLibraryBinding,
            ): Boolean =
                role.position.value == 0 &&
                    binding.matchesInvocation(role.call) &&
                    source.enclosing.hasSameProofAs(binding.invocation.enclosing) &&
                    source.executionOwner.hasSameProofAs(binding.invocation.executionOwner)
        }
    }

    @ConsistentCopyVisibility
    data class ScopeCallbackInput
    private constructor(
        override val source: ValueSite,
        override val target: ValueSite,
        override val binding: ReviewedLibraryBinding,
        val frame: ValueCallFrame,
    ) : ValueModelApplication {
        companion object {
            fun enter(
                source: ValueSite,
                frame: ValueCallFrame,
            ): Refinement<ScopeCallbackInput, ValueModelApplicationFailure> =
                when (val target = reviewedCallbackInput(source, frame)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            ScopeCallbackInput(
                                source,
                                target.value,
                                (frame.target as ValueReviewedCallbackTarget).binding,
                                frame,
                            )
                        )
                    is Refinement.Rejected -> target
                }
        }
    }

    @ConsistentCopyVisibility
    data class ScopeCallbackReturn
    private constructor(
        override val source: ValueSite,
        override val target: ValueSite,
        override val binding: ReviewedLibraryBinding,
        val frame: ValueCallFrame,
    ) : ValueModelApplication {
        companion object {
            fun leave(
                source: ValueSite,
                frame: ValueCallFrame,
            ): Refinement<ScopeCallbackReturn, ValueModelApplicationFailure> =
                when (val target = reviewedCallbackReturn(source, frame)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            ScopeCallbackReturn(
                                source,
                                target.value,
                                (frame.target as ValueReviewedCallbackTarget).binding,
                                frame,
                            )
                        )
                    is Refinement.Rejected -> target
                }
        }
    }

    @ConsistentCopyVisibility
    data class CollectionSelection
    private constructor(
        override val source: ValueSite,
        override val target: ValueSite,
        override val binding: ReviewedLibraryBinding,
        val condition: SelectedElementCondition,
    ) : ValueModelApplication {
        companion object {
            fun selectFirst(
                source: ValueSite,
                binding: ReviewedLibraryBinding,
            ): Refinement<CollectionSelection, ValueModelApplicationFailure> {
                if (binding.model != ReviewedValueModel.FirstOrNull)
                    return Refinement.Rejected(ValueModelApplicationFailure.RULE_MISMATCH)
                val role =
                    source.role as? ValueRole.Receiver
                        ?: return Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)
                if (source.enclosing.lease != binding.invocation.enclosing.lease)
                    return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
                if (!binding.matchesInvocation(role.call))
                    return Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)
                if (role.kind != ValueReceiverKind.EXTENSION)
                    return Refinement.Rejected(ValueModelApplicationFailure.RECEIVER_KIND_MISMATCH)
                val zero = (ValueElementIndex.parse(0) as Refinement.Refined).value
                val selected =
                    when (val selection = source.valuePath.select(ValuePathSegment.Element(zero))) {
                        is ValuePathSelection.Selected -> selection.path
                        ValuePathSelection.DifferentField ->
                            return Refinement.Rejected(ValueModelApplicationFailure.PATH_UNSELECTED)
                    }
                val target =
                    when (
                        val state =
                            binding.invocation
                                .resultSite()
                                .withState(
                                    valuePath = selected,
                                    callStack = source.callStack,
                                    predicates = source.predicates,
                                )
                    ) {
                        is Refinement.Refined -> state.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
                    }
                return when (val condition = SelectedElementCondition.fromSelection(source, target, zero)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            CollectionSelection(
                                source = source,
                                target = target,
                                binding = binding,
                                condition = condition.value,
                            )
                        )
                    is Refinement.Rejected -> condition
                }
            }
        }
    }
}
