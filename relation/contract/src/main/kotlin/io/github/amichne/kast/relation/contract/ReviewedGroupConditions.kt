package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Bucket placement depends on normal completion of the whole groupBy invocation and the selected grouping. */
@ConsistentCopyVisibility
data class ReviewedGroupMembership
private constructor(
    override val origin: ValueConditionOrigin,
    override val useRange: ExactDeclarationTextRange,
    val entry: ReviewedGroupBucket,
) : ValueCondition {
    companion object {
        fun fromReviewed(
            source: ValueSite,
            entry: ReviewedGroupBucket,
        ): Refinement<ReviewedGroupMembership, ValueSiteFailure> =
            if (entry.slot == MapEntrySlot.VALUE && entry.matches(source))
                Refinement.Refined(
                    ReviewedGroupMembership(
                        ValueConditionOrigin.fromSite(source),
                        entry.callbacks.selector.occurrence.range,
                        entry,
                    )
                )
            else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
    }
}

/** Key content depends on normal completion and selection of this key as the first equal-key representative. */
@ConsistentCopyVisibility
data class ReviewedGroupKeyRepresentative
private constructor(
    override val origin: ValueConditionOrigin,
    override val useRange: ExactDeclarationTextRange,
    val entry: ReviewedGroupBucket,
) : ValueCondition {
    companion object {
        fun fromReviewed(
            source: ValueSite,
            entry: ReviewedGroupBucket,
        ): Refinement<ReviewedGroupKeyRepresentative, ValueSiteFailure> =
            if (entry.placement == ReviewedGroupPlacement.KEY_CONTENT && entry.matches(source))
                Refinement.Refined(
                    ReviewedGroupKeyRepresentative(
                        ValueConditionOrigin.fromSite(source),
                        entry.callbacks.selector.occurrence.range,
                        entry,
                    )
                )
            else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
    }
}

internal fun StringBuilder.reviewedGroupMembership(condition: ReviewedGroupMembership) {
    field("REVIEWED_GROUP_MEMBERSHIP")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedGroupBucket(condition.entry)
}

internal fun StringBuilder.reviewedGroupKeyRepresentative(condition: ReviewedGroupKeyRepresentative) {
    field("REVIEWED_GROUP_KEY_REPRESENTATIVE")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedGroupBucket(condition.entry)
}

internal fun StringBuilder.reviewedGroupBucket(entry: ReviewedGroupBucket) {
    reviewedCallbackBinding(entry.callbacks.selector)
    when (val callbacks = entry.callbacks) {
        is ReviewedGroupCallbacks.OriginalElements -> field("ORIGINAL_ELEMENTS")
        is ReviewedGroupCallbacks.TransformedElements -> {
            field("TRANSFORMED_ELEMENTS")
            reviewedCallbackBinding(callbacks.transform)
        }
    }
    field(entry.placement.name)
    field(entry.selection.name)
    range(entry.sourceRange)
    executionContext(entry.sourceExecution.context)
    field(entry.callStack.frames.size.toString())
    entry.callStack.frames.forEach { callFrame(it) }
}
