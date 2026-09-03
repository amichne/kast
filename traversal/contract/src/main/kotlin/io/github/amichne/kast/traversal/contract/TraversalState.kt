package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TRAVERSAL_CONTINUATION_FINGERPRINT_LENGTH = 64

enum class TraversalDepthFailure {
    NEGATIVE,
    OVERFLOW,
}

@JvmInline
value class TraversalDepth private constructor(val value: Int) : Comparable<TraversalDepth> {
    override fun compareTo(other: TraversalDepth): Int = value.compareTo(other.value)

    companion object {
        val Zero: TraversalDepth = TraversalDepth(0)

        /**
         * Proof transition: `Int -> Refinement<TraversalDepth, TraversalDepthFailure>`.
         *
         * Establishes a finite non-negative semantic hop depth. [TraversalDepthFailure] is the
         * closed expected failure. Raw depth extraction may occur only in traversal accounting or
         * continuation transport.
         */
        fun parse(raw: Int): Refinement<TraversalDepth, TraversalDepthFailure> =
            if (raw >= 0) Refinement.Refined(TraversalDepth(raw))
            else Refinement.Rejected(TraversalDepthFailure.NEGATIVE)
    }

    /**
     * Proof transition: `TraversalDepth -> Refinement<TraversalDepth, TraversalDepthFailure>`.
     *
     * Establishes the next representable semantic hop. [TraversalDepthFailure] is the closed
     * expected failure. Raw integer extraction remains inside traversal accounting.
     */
    fun next(): Refinement<TraversalDepth, TraversalDepthFailure> =
        if (value == Int.MAX_VALUE) Refinement.Rejected(TraversalDepthFailure.OVERFLOW)
        else Refinement.Refined(TraversalDepth(value + 1))
}

enum class TraversalNodeFailure {
    LEASE_MISMATCH,
    SCOPE_MISMATCH,
}

/** Exact detached graph node; its endpoint fingerprint is compiler-grounded relation identity. */
@ConsistentCopyVisibility
data class TraversalNode private constructor(
    val endpoint: RelationEndpoint,
) : Comparable<TraversalNode> {
    val fingerprint: RelationEndpointFingerprint = endpoint.fingerprint

    override fun compareTo(other: TraversalNode): Int =
        fingerprint.value.compareTo(other.fingerprint.value)

    companion object {
        /**
         * Proof transition: `SymbolSelector -> TraversalNode`.
         *
         * Preserves the exact start selector's root, generation, scope, declaration, and compiler
         * identity. Raw symbol identity is never extracted by traversal.
         */
        fun start(selector: SymbolSelector): TraversalNode =
            TraversalNode(RelationEndpoint.subject(selector))

        /**
         * Proof transition: `(TraversalPlan, RelationEndpoint.Resolved) ->
         * Refinement<TraversalNode, TraversalNodeFailure>`.
         *
         * Establishes that a related compiler-grounded endpoint retains the plan's exact lease and
         * scope. [TraversalNodeFailure] is the closed expected failure. Raw compiler values remain
         * outside traversal at the relation reader boundary.
         */
        fun related(
            plan: TraversalPlan,
            endpoint: RelationEndpoint.Resolved,
        ): Refinement<TraversalNode, TraversalNodeFailure> = when {
            endpoint.lease != plan.start.lease ->
                Refinement.Rejected(TraversalNodeFailure.LEASE_MISMATCH)
            endpoint.scope != plan.scope ->
                Refinement.Rejected(TraversalNodeFailure.SCOPE_MISMATCH)
            else -> Refinement.Refined(TraversalNode(endpoint))
        }

        /** Restores a detached node from a verified self-contained exact selector. */
        fun restore(
            plan: TraversalPlan,
            selector: SymbolSelector,
        ): Refinement<TraversalNode, TraversalNodeFailure> = when {
            selector.lease != plan.start.lease ->
                Refinement.Rejected(TraversalNodeFailure.LEASE_MISMATCH)
            selector.scope != plan.scope ->
                Refinement.Rejected(TraversalNodeFailure.SCOPE_MISMATCH)
            else -> Refinement.Refined(TraversalNode(RelationEndpoint.subject(selector)))
        }
    }
}

enum class TraversalFrontierEntryFailure {
    LEASE_MISMATCH,
    SCOPE_MISMATCH,
}

@ConsistentCopyVisibility
data class TraversalFrontierEntry private constructor(
    val node: TraversalNode,
    val depth: TraversalDepth,
) : Comparable<TraversalFrontierEntry> {
    override fun compareTo(other: TraversalFrontierEntry): Int =
        compareValuesBy(this, other, TraversalFrontierEntry::depth, { it.node.fingerprint.value })

    companion object {
        internal fun initial(plan: TraversalPlan): TraversalFrontierEntry =
            TraversalFrontierEntry(TraversalNode.start(plan.start), TraversalDepth.Zero)

        /**
         * Proof transition: `(TraversalPlan, TraversalNode, TraversalDepth) ->
         * Refinement<TraversalFrontierEntry, TraversalFrontierEntryFailure>`.
         *
         * Establishes a frontier entry bound to the plan's exact lease and scope.
         * [TraversalFrontierEntryFailure] is the closed expected failure. Raw frontier state may
         * enter only from the pure engine or continuation transport.
         */
        fun create(
            plan: TraversalPlan,
            node: TraversalNode,
            depth: TraversalDepth,
        ): Refinement<TraversalFrontierEntry, TraversalFrontierEntryFailure> = when {
            node.endpoint.lease != plan.start.lease ->
                Refinement.Rejected(TraversalFrontierEntryFailure.LEASE_MISMATCH)
            node.endpoint.scope != plan.scope ->
                Refinement.Rejected(TraversalFrontierEntryFailure.SCOPE_MISMATCH)
            else -> Refinement.Refined(TraversalFrontierEntry(node, depth))
        }
    }
}

enum class TraversalPendingReadFailure {
    FRONTIER_MISMATCH,
    SELECTOR_MISMATCH,
    MEANING_MISMATCH,
    SCOPE_MISMATCH,
    GENERATION_MISMATCH,
}

@ConsistentCopyVisibility
data class TraversalPendingRead private constructor(
    val entry: TraversalFrontierEntry,
    val relationContinuation: RelationContinuation,
) {
    companion object {
        /**
         * Proof transition: `(TraversalPlan, TraversalFrontierEntry, RelationContinuation) ->
         * Refinement<TraversalPendingRead, TraversalPendingReadFailure>`.
         *
         * Establishes that incomplete one-hop work resumes the same exact node, meaning, and
         * generation inside the plan scope. [TraversalPendingReadFailure] is the closed expected
         * failure. Raw relation continuation decoding may occur only before this boundary.
         */
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            continuation: RelationContinuation,
        ): Refinement<TraversalPendingRead, TraversalPendingReadFailure> = when {
            entry.node.endpoint.lease != plan.start.lease ||
            entry.node.endpoint.scope != plan.scope ->
                Refinement.Rejected(TraversalPendingReadFailure.FRONTIER_MISMATCH)
            continuation.subject != entry.node.fingerprint ->
                Refinement.Rejected(TraversalPendingReadFailure.SELECTOR_MISMATCH)
            continuation.meaning != plan.meaning ->
                Refinement.Rejected(TraversalPendingReadFailure.MEANING_MISMATCH)
            continuation.scope != RelationScopeFingerprint.from(entry.node.endpoint) ->
                Refinement.Rejected(TraversalPendingReadFailure.SCOPE_MISMATCH)
            continuation.generation != plan.start.lease.generation ->
                Refinement.Rejected(TraversalPendingReadFailure.GENERATION_MISMATCH)
            else -> Refinement.Refined(TraversalPendingRead(entry, continuation))
        }
    }
}

sealed interface TraversalPendingState {
    data object None : TraversalPendingState

    @ConsistentCopyVisibility
    data class Active internal constructor(
        val read: TraversalPendingRead,
    ) : TraversalPendingState

    companion object {
        fun active(read: TraversalPendingRead): Active = Active(read)
    }
}

enum class TraversalCheckpointFailure {
    IDENTITY_MISMATCH,
    NON_DETERMINISTIC_FRONTIER,
    DUPLICATE_FRONTIER_NODE,
    FRONTIER_NODE_MISMATCH,
    VISITED_FRONTIER_OVERLAP,
    PENDING_NODE_NOT_VISITED,
    PENDING_NODE_IN_FRONTIER,
}

/** Detached deterministic traversal state carried by a qualified continuation. */
class TraversalCheckpoint private constructor(
    val identity: TraversalIdentityFingerprint,
    val frontier: List<TraversalFrontierEntry>,
    val visited: Set<RelationEndpointFingerprint>,
    val pending: TraversalPendingState,
    val terminalRelationLimitations: Set<RelationLimitation>,
) {
    companion object {
        /**
         * Proof transition: `TraversalPlan -> TraversalCheckpoint`.
         *
         * Establishes the exact unvisited depth-zero start frontier with no hidden prior work.
         */
        fun initial(plan: TraversalPlan): TraversalCheckpoint = TraversalCheckpoint(
            identity = plan.identity,
            frontier = listOf(TraversalFrontierEntry.initial(plan)),
            visited = emptySet(),
            pending = TraversalPendingState.None,
            terminalRelationLimitations = emptySet(),
        )

        /**
         * Proof transition: `(TraversalPlan, frontier, visited, pending) ->
         * Refinement<TraversalCheckpoint, TraversalCheckpointFailure>`.
         *
         * Establishes exact plan identity, deterministic unique frontier order, scope/lease
         * retention, cycle state, and a pending read owned by one visited node.
         * [TraversalCheckpointFailure] is the closed expected failure. Raw collections may enter
         * only from the pure traversal engine or continuation transport.
         */
        fun create(
            plan: TraversalPlan,
            frontier: List<TraversalFrontierEntry>,
            visited: Set<RelationEndpointFingerprint>,
            pending: TraversalPendingState,
            terminalRelationLimitations: Set<RelationLimitation> = emptySet(),
        ): Refinement<TraversalCheckpoint, TraversalCheckpointFailure> {
            if (frontier != frontier.sorted()) {
                return Refinement.Rejected(
                    TraversalCheckpointFailure.NON_DETERMINISTIC_FRONTIER,
                )
            }
            if (frontier.map { it.node.fingerprint }.distinct().size != frontier.size) {
                return Refinement.Rejected(TraversalCheckpointFailure.DUPLICATE_FRONTIER_NODE)
            }
            if (frontier.any {
                    it.node.endpoint.lease != plan.start.lease || it.node.endpoint.scope != plan.scope
                }
            ) {
                return Refinement.Rejected(TraversalCheckpointFailure.FRONTIER_NODE_MISMATCH)
            }
            val frontierIds = frontier.mapTo(linkedSetOf()) { it.node.fingerprint }
            if (frontierIds.any(visited::contains)) {
                return Refinement.Rejected(TraversalCheckpointFailure.VISITED_FRONTIER_OVERLAP)
            }
            if (
                pending is TraversalPendingState.Active &&
                pending.read.entry.node.fingerprint !in visited
            ) {
                return Refinement.Rejected(TraversalCheckpointFailure.PENDING_NODE_NOT_VISITED)
            }
            if (
                pending is TraversalPendingState.Active &&
                pending.read.entry.node.fingerprint in frontierIds
            ) {
                return Refinement.Rejected(TraversalCheckpointFailure.PENDING_NODE_IN_FRONTIER)
            }
            return Refinement.Refined(
                TraversalCheckpoint(
                    plan.identity,
                    frontier.toList(),
                    visited.toSet(),
                    pending,
                    terminalRelationLimitations.toSortedSet(compareBy { it.ordinal }).toSet(),
                ),
            )
        }
    }
}

enum class TraversalContinuationFailure {
    IDENTITY_MISMATCH,
    INTEGRITY_MISMATCH,
}

enum class TraversalContinuationFingerprintFailure {
    INVALID_SHA256,
}

@JvmInline
value class TraversalContinuationFingerprint private constructor(val value: String) {
    init {
        require(
            value.length == TRAVERSAL_CONTINUATION_FINGERPRINT_LENGTH &&
            value.all { character -> character in '0'..'9' || character in 'a'..'f' },
        )
    }

    companion object {
        fun parse(
            raw: String,
        ): Refinement<TraversalContinuationFingerprint, TraversalContinuationFingerprintFailure> =
            if (
                raw.length == TRAVERSAL_CONTINUATION_FINGERPRINT_LENGTH &&
                raw.all { character -> character in '0'..'9' || character in 'a'..'f' }
            ) {
                Refinement.Refined(TraversalContinuationFingerprint(raw))
            } else {
                Refinement.Rejected(TraversalContinuationFingerprintFailure.INVALID_SHA256)
            }

        internal fun established(raw: String): TraversalContinuationFingerprint =
            TraversalContinuationFingerprint(raw)
    }
}

/** Opaque deterministic resume state bound to one traversal semantic identity. */
class TraversalContinuation private constructor(
    val start: SymbolSelector,
    val meaning: RelationMeaning,
    val identity: TraversalIdentityFingerprint,
    val checkpoint: TraversalCheckpoint,
    val fingerprint: TraversalContinuationFingerprint,
) {
    companion object {
        /**
         * Proof transition: `(TraversalPlan, TraversalCheckpoint) ->
         * Refinement<TraversalContinuation, TraversalContinuationFailure>`.
         *
         * Establishes an opaque continuation bound to the plan's exact selector, meaning, root,
         * generation, scope, frontier, visited set, and pending relation page.
         * [TraversalContinuationFailure] is the closed expected failure. Raw encoding is permitted
         * only at continuation transport.
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
                checkpoint.terminalRelationLimitations.sortedBy { it.ordinal }.forEach { limitation ->
                    appendTraversalField(limitation.name)
                }
                when (val pending = checkpoint.pending) {
                    TraversalPendingState.None -> appendTraversalField("-")
                    is TraversalPendingState.Active -> appendTraversalField(
                        pending.read.relationContinuation.fingerprint.value,
                    )
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            return Refinement.Refined(
                TraversalContinuation(
                    plan.start,
                    plan.meaning,
                    plan.identity,
                    checkpoint,
                    TraversalContinuationFingerprint.established(
                        digest.joinToString(separator = "") { byte ->
                            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                        },
                    ),
                ),
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
                is Refinement.Refined -> if (issued.value.fingerprint == fingerprint) {
                    issued
                } else {
                    Refinement.Rejected(TraversalContinuationFailure.INTEGRITY_MISMATCH)
                }
            }
    }
}
