package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Membership depends on the predicate accepting the tracked element; this fact asserts no execution. */
@ConsistentCopyVisibility
data class ReviewedPredicateAccepts
private constructor(
    override val origin: ValueConditionOrigin,
    override val useRange: ExactDeclarationTextRange,
    val binding: ReviewedCallbackValueBinding,
) : ValueCondition {
    companion object {
        fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedPredicateAccepts, ValueSiteFailure> =
            if (binding.admitsFilteredReceiver(source))
                Refinement.Refined(
                    ReviewedPredicateAccepts(ValueConditionOrigin.fromSite(source), binding.occurrence.range, binding)
                )
            else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
    }
}

/** A nullable callback return contributes an output element only when that returned value is non-null. */
@ConsistentCopyVisibility
data class ReviewedCallbackResultNonNull
private constructor(
    override val origin: ValueConditionOrigin,
    override val useRange: ExactDeclarationTextRange,
    val binding: ReviewedCallbackValueBinding,
) : ValueCondition {
    companion object {
        fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
        ): Refinement<ReviewedCallbackResultNonNull, ValueSiteFailure> =
            if (binding.admitsNonNullReturn(source))
                Refinement.Refined(
                    ReviewedCallbackResultNonNull(
                        ValueConditionOrigin.fromSite(source),
                        binding.occurrence.range,
                        binding,
                    )
                )
            else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
    }
}

private fun ReviewedCallbackValueBinding.admitsNonNullReturn(source: ValueSite): Boolean =
    selected.operation == ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL &&
        source.role == ValueRole.Return &&
        source.basis == basis &&
        source.executionOwner == ValueExecutionOwner.reviewedCallback(this)

internal fun StringBuilder.reviewedPredicateAccepts(condition: ReviewedPredicateAccepts) {
    field("REVIEWED_PREDICATE_ACCEPTS")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedCallbackBinding(condition.binding)
}

internal fun StringBuilder.reviewedCallbackResultNonNull(condition: ReviewedCallbackResultNonNull) {
    field("REVIEWED_CALLBACK_RESULT_NON_NULL")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedCallbackBinding(condition.binding)
}
