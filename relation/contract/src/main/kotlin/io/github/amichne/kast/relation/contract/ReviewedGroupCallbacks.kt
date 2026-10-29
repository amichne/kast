package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Group callbacks retain separate compiler-owned bodies under one selected invocation. */
sealed interface ReviewedGroupCallbacks {
    val selector: ReviewedCallbackValueBinding

    @ConsistentCopyVisibility
    data class OriginalElements private constructor(override val selector: ReviewedCallbackValueBinding) :
        ReviewedGroupCallbacks {
        companion object {
            fun fromReviewed(
                selector: ReviewedCallbackValueBinding
            ): Refinement<OriginalElements, ValueFlowUnsupportedCause> =
                if (
                    selector.selected.operation == ReviewedLibraryOperation.ITERABLE_GROUP_BY &&
                        selector.position.value == 0
                )
                    Refinement.Refined(OriginalElements(selector))
                else Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
        }
    }

    @ConsistentCopyVisibility
    data class TransformedElements
    private constructor(
        override val selector: ReviewedCallbackValueBinding,
        val transform: ReviewedCallbackValueBinding,
    ) : ReviewedGroupCallbacks {
        companion object {
            fun fromReviewed(
                selector: ReviewedCallbackValueBinding,
                transform: ReviewedCallbackValueBinding,
            ): Refinement<TransformedElements, ValueFlowUnsupportedCause> {
                if (!selector.matchesGroupTransform(transform))
                    return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                return Refinement.Refined(TransformedElements(selector, transform))
            }
        }
    }

    val retainedBytes: Long
        get() =
            when (this) {
                is OriginalElements -> selector.retainedBytes
                is TransformedElements -> selector.retainedBytes.addBytes(transform.retainedBytes)
            }
}

private fun ReviewedCallbackValueBinding.matchesGroupTransform(transform: ReviewedCallbackValueBinding): Boolean {
    if (
        selected.operation != ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM ||
            transform.selected.operation != ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM
    )
        return false
    if (position.value != 0 || transform.position.value != 1 || body == transform.body) return false
    if (basis != transform.basis || enclosing.valueIdentity != transform.enclosing.valueIdentity) return false
    if (target != transform.target || occurrence != transform.occurrence) return false
    if (artifact != transform.artifact || parent != transform.parent) return false
    return hasDisjointGroupBody(transform)
}

private fun ReviewedCallbackValueBinding.hasDisjointGroupBody(transform: ReviewedCallbackValueBinding): Boolean =
    body.range.endExclusive <= transform.body.range.startInclusive ||
        transform.body.range.endExclusive <= body.range.startInclusive
