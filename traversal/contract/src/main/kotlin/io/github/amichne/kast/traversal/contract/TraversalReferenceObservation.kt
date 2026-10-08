package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence

enum class TraversalReferenceObservationFailure {
    AUTHORITY_MISMATCH,
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    DEPTH_MISMATCH,
}

/** Compiler-confirmed occurrence observed while expanding one node; ownership does not invent a graph edge. */
@ConsistentCopyVisibility
data class TraversalReferenceObservation
private constructor(
    val entry: TraversalFrontierEntry,
    val reference: RelationReferenceOccurrence,
) : Comparable<TraversalReferenceObservation> {
    override fun compareTo(other: TraversalReferenceObservation): Int =
        compareValuesBy(this, other, { it.entry.depth }, { it.reference })

    companion object {
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            reference: RelationReferenceOccurrence,
        ): Refinement<TraversalReferenceObservation, TraversalReferenceObservationFailure> =
            when {
                reference.authority != plan.start.lease.identity ->
                    Refinement.Rejected(TraversalReferenceObservationFailure.AUTHORITY_MISMATCH)
                reference.target.fingerprint != entry.node.fingerprint ->
                    Refinement.Rejected(TraversalReferenceObservationFailure.SUBJECT_MISMATCH)
                reference.meaning != plan.meaning ->
                    Refinement.Rejected(TraversalReferenceObservationFailure.MEANING_MISMATCH)
                !plan.budget.extent.permitsExpansion(entry.depth) ->
                    Refinement.Rejected(TraversalReferenceObservationFailure.DEPTH_MISMATCH)
                else -> Refinement.Refined(TraversalReferenceObservation(entry, reference))
            }
    }
}
