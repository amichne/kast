package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation

/** Disposition of unenumerated neighbors; neither variant estimates omitted subtrees. */
enum class TraversalExpansionRemainder {
    CONTINUATION_RETAINED,
    NOT_EXPLORED,
}

enum class TraversalPartialExpansionFailure {
    EMPTY_LIMITATIONS,
    ENTRY_OUTSIDE_PLAN,
}

/** One qualified relation read on this page, retaining the subject and its depth (root depth is zero). */
@ConsistentCopyVisibility
data class TraversalPartialExpansion
private constructor(
    val entry: TraversalFrontierEntry,
    val limitations: Set<RelationLimitation>,
    val remainder: TraversalExpansionRemainder,
) {
    companion object {
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            limitations: Set<RelationLimitation>,
            remainder: TraversalExpansionRemainder,
        ): Refinement<TraversalPartialExpansion, TraversalPartialExpansionFailure> =
            when {
                limitations.isEmpty() -> Refinement.Rejected(TraversalPartialExpansionFailure.EMPTY_LIMITATIONS)
                entry.node.endpoint.lease != plan.start.lease ||
                    entry.node.endpoint.scope != plan.scope ||
                    entry.depth.value >= plan.budget.depth.value ->
                    Refinement.Rejected(TraversalPartialExpansionFailure.ENTRY_OUTSIDE_PLAN)
                else ->
                    Refinement.Refined(
                        TraversalPartialExpansion(
                            entry,
                            limitations.toSortedSet(compareBy { it.ordinal }).toSet(),
                            remainder,
                        )
                    )
            }
    }
}
