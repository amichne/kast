package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class ValueModelApplicationFailure {
    SOURCE_MISMATCH,
    BASIS_MISMATCH,
    RECEIVER_KIND_MISMATCH,
    RULE_MISMATCH,
    PATH_UNSELECTED,
    RULE_NOT_APPLIED,
}

/** A reviewed condition grants no compiler-established non-null or nonempty fact. */
data class SelectedElementCondition internal constructor(
    val receiver: ValueSite,
    val result: ValueSite,
    val index: ValueElementIndex,
)

/** This draft stays outside source sets. Projection assertions and native applications remain unexecuted. */
sealed interface ValueModelApplication {
    val source: ValueSite
    val target: ValueSite
    val binding: ReviewedLibraryBinding
    val reference: ModelRuleReference
        get() = binding.model.reference
    val invocation: ValueInvocation
        get() = binding.invocation

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
            val role = source.role as? ValueRole.Receiver
                ?: return Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)
            if (source.enclosing.lease != binding.invocation.enclosing.lease)
                return Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)
            if (role.call != binding.invocation || role.call.callable != binding.invocation.callable)
                return Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)
            if (role.kind != ValueReceiverKind.EXTENSION)
                return Refinement.Rejected(ValueModelApplicationFailure.RECEIVER_KIND_MISMATCH)
            val zero = (ValueElementIndex.parse(0) as Refinement.Refined).value
            if (source.valuePath.select(ValuePathSegment.Element(zero)) is ValuePathSelection.DifferentField)
                return Refinement.Rejected(ValueModelApplicationFailure.PATH_UNSELECTED)
                return Refinement.Rejected(ValueModelApplicationFailure.RULE_NOT_APPLIED)
            }
        }
    }
}
