package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning

enum class TraversalRecordFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    GENERATION_MISMATCH,
    SCOPE_MISMATCH,
    NON_RELATED_ENDPOINT,
    DEPTH_EXCEEDS_PLAN,
}

/** One exact relation fact at its deterministic breadth-first traversal depth. */
@ConsistentCopyVisibility
data class TraversalRecord
private constructor(
    val origin: RelationEndpointFingerprint,
    val depth: TraversalDepth,
    val fact: RelationFact,
    val related: RelationEndpoint.Resolved,
) : Comparable<TraversalRecord> {
    override fun compareTo(other: TraversalRecord): Int =
        compareValuesBy(this, other, TraversalRecord::depth, { it.origin.value }, { it.fact })

    fun canonicalProjection(): String = buildString {
        appendTraversalField(depth.value.toString())
        appendTraversalField(origin.value)
        appendTraversalField(fact.canonicalProjection())
    }

    companion object {
        /**
         * Proof transition: `(TraversalPlan, origin, TraversalDepth, RelationFact) -> Refinement<TraversalRecord,
         * TraversalRecordFailure>`.
         *
         * Establishes the plan's exact subject, meaning, root, generation, scope, hop depth, and one compiler-grounded
         * related endpoint. [TraversalRecordFailure] is the closed expected failure. Raw relation facts may enter only
         * from the module-private one-hop reader.
         */
        fun create(
            plan: TraversalPlan,
            origin: RelationEndpointFingerprint,
            depth: TraversalDepth,
            fact: RelationFact,
        ): Refinement<TraversalRecord, TraversalRecordFailure> {
            if (fact.subject.fingerprint.value != origin.value) {
                return Refinement.Rejected(TraversalRecordFailure.SUBJECT_MISMATCH)
            }
            if (fact.meaning != plan.meaning) {
                return Refinement.Rejected(TraversalRecordFailure.MEANING_MISMATCH)
            }
            if (fact.authority != plan.start.lease.identity) {
                return Refinement.Rejected(TraversalRecordFailure.GENERATION_MISMATCH)
            }
            if (fact.source.scope != plan.scope || fact.target.scope != plan.scope) {
                return Refinement.Rejected(TraversalRecordFailure.SCOPE_MISMATCH)
            }
            if (depth.value > plan.budget.depth.value) {
                return Refinement.Rejected(TraversalRecordFailure.DEPTH_EXCEEDS_PLAN)
            }
            val related =
                when (plan.meaning) {
                    RelationMeaning.Callees -> fact.target
                    RelationMeaning.References,
                    RelationMeaning.Callers,
                    RelationMeaning.Implementations,
                    RelationMeaning.Inheritors,
                    RelationMeaning.Overrides,
                    RelationMeaning.TypeUses -> fact.source
                }
            return if (related is RelationEndpoint.Resolved) {
                Refinement.Refined(TraversalRecord(origin, depth, fact, related))
            } else {
                Refinement.Rejected(TraversalRecordFailure.NON_RELATED_ENDPOINT)
            }
        }
    }
}
