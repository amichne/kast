package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint

enum class TraversalCallableObservationFailure {
    AUTHORITY_MISMATCH,
    SUBJECT_MISMATCH,
    DOMAIN_MISMATCH,
    DEPTH_MISMATCH,
    MEANING_MISMATCH,
}

/** A symbolic callee or resolved source boundary retains its exact frontier without becoming a named call edge. */
@ConsistentCopyVisibility
data class TraversalCallableObservation
private constructor(
    val entry: TraversalFrontierEntry,
    val observation: RelationCallableObservation,
) : Comparable<TraversalCallableObservation> {
    override fun compareTo(other: TraversalCallableObservation): Int =
        compareValuesBy(this, other, { it.entry.depth }, { it.observation })

    companion object {
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            observation: RelationCallableObservation,
        ): Refinement<TraversalCallableObservation, TraversalCallableObservationFailure> =
            when {
                observation.basis != plan.start.lease ->
                    Refinement.Rejected(TraversalCallableObservationFailure.AUTHORITY_MISMATCH)
                observation.subject != entry.node.fingerprint ->
                    Refinement.Rejected(TraversalCallableObservationFailure.SUBJECT_MISMATCH)
                observation.requestedDomain != plan.expansion ||
                    observation.effectiveDomain != RelationScopeFingerprint.from(entry.node.endpoint, plan.expansion) ->
                    Refinement.Rejected(TraversalCallableObservationFailure.DOMAIN_MISMATCH)
                plan.meaning != io.github.amichne.kast.relation.contract.RelationMeaning.Callees ->
                    Refinement.Rejected(TraversalCallableObservationFailure.MEANING_MISMATCH)
                !plan.admitsEndpoint(entry.node.endpoint) || !plan.budget.extent.permitsExpansion(entry.depth) ->
                    Refinement.Rejected(TraversalCallableObservationFailure.DEPTH_MISMATCH)
                else -> Refinement.Refined(TraversalCallableObservation(entry, observation))
            }
    }
}
