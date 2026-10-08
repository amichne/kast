package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint

enum class TraversalCallbackObservationFailure {
    AUTHORITY_MISMATCH,
    SUBJECT_MISMATCH,
    DOMAIN_MISMATCH,
    DEPTH_MISMATCH,
    MEANING_MISMATCH,
}

/** A callback ownership exclusion retains its exact frontier without becoming a named call edge. */
@ConsistentCopyVisibility
data class TraversalCallbackObservation
private constructor(
    val entry: TraversalFrontierEntry,
    val observation: RelationCallbackObservation,
) : Comparable<TraversalCallbackObservation> {
    override fun compareTo(other: TraversalCallbackObservation): Int =
        compareValuesBy(this, other, { it.entry.depth }, { it.observation })

    companion object {
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            observation: RelationCallbackObservation,
        ): Refinement<TraversalCallbackObservation, TraversalCallbackObservationFailure> =
            when {
                observation.basis != plan.start.lease ->
                    Refinement.Rejected(TraversalCallbackObservationFailure.AUTHORITY_MISMATCH)
                observation.subject != entry.node.fingerprint ->
                    Refinement.Rejected(TraversalCallbackObservationFailure.SUBJECT_MISMATCH)
                observation.requestedDomain != plan.expansion ||
                    observation.effectiveDomain != RelationScopeFingerprint.from(entry.node.endpoint, plan.expansion) ->
                    Refinement.Rejected(TraversalCallbackObservationFailure.DOMAIN_MISMATCH)
                observation.meaning != plan.meaning ->
                    Refinement.Rejected(TraversalCallbackObservationFailure.MEANING_MISMATCH)
                !plan.admitsEndpoint(entry.node.endpoint) || !plan.budget.extent.permitsExpansion(entry.depth) ->
                    Refinement.Rejected(TraversalCallbackObservationFailure.DEPTH_MISMATCH)
                else -> Refinement.Refined(TraversalCallbackObservation(entry, observation))
            }
    }
}
