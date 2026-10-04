package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class TraversalContinuationFailure {
    IDENTITY_MISMATCH,
    INTEGRITY_MISMATCH,
}

/** Opaque deterministic resume state bound to one traversal semantic identity. */
class TraversalContinuation
private constructor(
    val start: SymbolSelector,
    val meaning: RelationMeaning,
    val identity: TraversalIdentityFingerprint,
    val checkpoint: TraversalCheckpoint,
    val fingerprint: TraversalContinuationFingerprint,
    val strategy: TraversalStrategy,
    val maximumDepth: TraversalDepthLimit,
    val expansion: io.github.amichne.kast.relation.contract.RelationSearchBoundary,
) {
    companion object {
        /**
         * Proof transition: `(TraversalPlan, TraversalCheckpoint) -> Refinement<TraversalContinuation,
         * TraversalContinuationFailure>`.
         *
         * Establishes an opaque continuation bound to the plan's exact selector, meaning, root, generation, scope,
         * frontier, visited set, and pending relation page. [TraversalContinuationFailure] is the closed expected
         * failure. Raw encoding is permitted only at continuation transport.
         */
        fun issue(
            plan: TraversalPlan,
            checkpoint: TraversalCheckpoint,
        ): Refinement<TraversalContinuation, TraversalContinuationFailure> {
            if (checkpoint.identity != plan.identity) {
                return Refinement.Rejected(TraversalContinuationFailure.IDENTITY_MISMATCH)
            }
            val canonical = buildString {
                appendTraversalField(plan.identity.value)
                appendTraversalField(checkpoint.progress.checkpointSequence.toString())
                appendTraversalField(checkpoint.progress.totalReads.toString())
                appendTraversalField(checkpoint.progress.totalEdges.toString())
                appendTraversalField(checkpoint.progress.maximumDepthReached.toString())
                appendTraversalField(checkpoint.frontier.size.toString())
                checkpoint.frontier.forEach { entry ->
                    appendTraversalField(entry.depth.value.toString())
                    appendTraversalField(entry.node.fingerprint.value)
                }
                appendTraversalField(checkpoint.visited.size.toString())
                checkpoint.visited.sortedBy(RelationEndpointFingerprint::value).forEach { visited ->
                    appendTraversalField(visited.value)
                }
                appendTraversalField(checkpoint.terminalRelationLimitations.size.toString())
                checkpoint.terminalRelationLimitations
                    .sortedBy { it.ordinal }
                    .forEach { limitation ->
                        appendTraversalField(limitation.name)
                    }
                appendTraversalField(checkpoint.retainedOmissions.size.toString())
                checkpoint.retainedOmissions.forEach { appendRetainedExpansion(it) }
                when (val pending = checkpoint.pending) {
                    TraversalPendingState.None -> appendTraversalField("-")
                    is TraversalPendingState.Active ->
                        appendTraversalField(pending.read.relationContinuation.fingerprint.value)
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            return Refinement.Refined(
                TraversalContinuation(
                    plan.start,
                    plan.meaning,
                    plan.identity,
                    checkpoint,
                    TraversalContinuationFingerprint.established(java.util.HexFormat.of().formatHex(digest)),
                    plan.strategy,
                    plan.budget.depth,
                    plan.expansion,
                )
            )
        }

        /** Restores decoded checkpoint authority only when its deterministic digest is exact. */
        fun restore(
            plan: TraversalPlan,
            checkpoint: TraversalCheckpoint,
            fingerprint: TraversalContinuationFingerprint,
        ): Refinement<TraversalContinuation, TraversalContinuationFailure> =
            when (val issued = issue(plan, checkpoint)) {
                is Refinement.Rejected -> issued
                is Refinement.Refined ->
                    if (issued.value.fingerprint == fingerprint) {
                        issued
                    } else {
                        Refinement.Rejected(TraversalContinuationFailure.INTEGRITY_MISMATCH)
                    }
            }
    }
}
