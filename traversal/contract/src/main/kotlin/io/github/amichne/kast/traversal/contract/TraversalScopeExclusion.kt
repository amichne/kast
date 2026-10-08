package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint

enum class TraversalScopeExclusionFailure {
    AUTHORITY_MISMATCH,
    SUBJECT_MISMATCH,
    DOMAIN_MISMATCH,
    DEPTH_MISMATCH,
}

/** A domain exit at one retained traversal node; it never becomes a graph endpoint or a missing edge. */
@ConsistentCopyVisibility
data class TraversalScopeExclusion
private constructor(
    val entry: TraversalFrontierEntry,
    val exclusion: RelationScopeExclusion,
) : Comparable<TraversalScopeExclusion> {
    override fun compareTo(other: TraversalScopeExclusion): Int =
        compareValuesBy(this, other, { it.entry.depth }, { it.exclusion })

    companion object {
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            exclusion: RelationScopeExclusion,
        ): Refinement<TraversalScopeExclusion, TraversalScopeExclusionFailure> =
            when {
                exclusion.basis != plan.start.lease ->
                    Refinement.Rejected(TraversalScopeExclusionFailure.AUTHORITY_MISMATCH)
                exclusion.subject != entry.node.fingerprint ->
                    Refinement.Rejected(TraversalScopeExclusionFailure.SUBJECT_MISMATCH)
                exclusion.requestedDomain != plan.expansion ||
                    exclusion.effectiveDomain != RelationScopeFingerprint.from(entry.node.endpoint, plan.expansion) ->
                    Refinement.Rejected(TraversalScopeExclusionFailure.DOMAIN_MISMATCH)
                !plan.admitsEndpoint(entry.node.endpoint) || !plan.budget.extent.permitsExpansion(entry.depth) ->
                    Refinement.Rejected(TraversalScopeExclusionFailure.DEPTH_MISMATCH)
                else -> Refinement.Refined(TraversalScopeExclusion(entry, exclusion))
            }
    }
}
