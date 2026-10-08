package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.util.Collections

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
         * Establishes a finite non-negative semantic hop depth. [TraversalDepthFailure] is the closed expected failure.
         * Raw depth extraction may occur only in traversal accounting or continuation transport.
         */
        fun parse(raw: Int): Refinement<TraversalDepth, TraversalDepthFailure> =
            if (raw >= 0) Refinement.Refined(TraversalDepth(raw))
            else Refinement.Rejected(TraversalDepthFailure.NEGATIVE)
    }

    /**
     * Proof transition: `TraversalDepth -> Refinement<TraversalDepth, TraversalDepthFailure>`.
     *
     * Establishes the next representable semantic hop. [TraversalDepthFailure] is the closed expected failure. Raw
     * integer extraction remains inside traversal accounting.
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
data class TraversalNode private constructor(val endpoint: RelationEndpoint) : Comparable<TraversalNode> {
    val fingerprint: RelationEndpointFingerprint = endpoint.fingerprint

    override fun compareTo(other: TraversalNode): Int = fingerprint.value.compareTo(other.fingerprint.value)

    companion object {
        /**
         * Proof transition: `SymbolSelector -> TraversalNode`.
         *
         * Preserves the exact start selector's root, generation, scope, declaration, and compiler identity. Raw symbol
         * identity is never extracted by traversal.
         */
        fun start(selector: SymbolSelector): TraversalNode = TraversalNode(RelationEndpoint.subject(selector))

        /**
         * Proof transition: `(TraversalPlan, RelationEndpoint.Resolved) -> Refinement<TraversalNode,
         * TraversalNodeFailure>`.
         *
         * Establishes that a related compiler-grounded endpoint retains the plan's exact lease and scope.
         * [TraversalNodeFailure] is the closed expected failure. Raw compiler values remain outside traversal at the
         * relation reader boundary.
         */
        fun related(
            plan: TraversalPlan,
            endpoint: RelationEndpoint.Resolved,
        ): Refinement<TraversalNode, TraversalNodeFailure> =
            when {
                endpoint.lease != plan.start.lease -> Refinement.Rejected(TraversalNodeFailure.LEASE_MISMATCH)
                !plan.admitsEndpoint(endpoint) -> Refinement.Rejected(TraversalNodeFailure.SCOPE_MISMATCH)
                else -> Refinement.Refined(TraversalNode(endpoint))
            }

        /** Restores a detached node from a verified self-contained exact selector. */
        fun restore(
            plan: TraversalPlan,
            selector: SymbolSelector,
        ): Refinement<TraversalNode, TraversalNodeFailure> =
            when {
                selector.lease != plan.start.lease -> Refinement.Rejected(TraversalNodeFailure.LEASE_MISMATCH)
                selector.scope != plan.scope -> Refinement.Rejected(TraversalNodeFailure.SCOPE_MISMATCH)
                else -> Refinement.Refined(TraversalNode(RelationEndpoint.subject(selector)))
            }
    }
}

enum class TraversalFrontierEntryFailure {
    LEASE_MISMATCH,
    SCOPE_MISMATCH,
}

@ConsistentCopyVisibility
data class TraversalFrontierEntry
private constructor(
    val node: TraversalNode,
    val depth: TraversalDepth,
) : Comparable<TraversalFrontierEntry> {
    override fun compareTo(other: TraversalFrontierEntry): Int =
        compareValuesBy(this, other, TraversalFrontierEntry::depth, { it.node.fingerprint.value })

    companion object {
        internal fun initial(plan: TraversalPlan): TraversalFrontierEntry =
            TraversalFrontierEntry(TraversalNode.start(plan.start), TraversalDepth.Zero)

        /**
         * Proof transition: `(TraversalPlan, TraversalNode, TraversalDepth) -> Refinement<TraversalFrontierEntry,
         * TraversalFrontierEntryFailure>`.
         *
         * Establishes a frontier entry bound to the plan's exact lease and scope. [TraversalFrontierEntryFailure] is
         * the closed expected failure. Raw frontier state may enter only from the pure engine or continuation
         * transport.
         */
        fun create(
            plan: TraversalPlan,
            node: TraversalNode,
            depth: TraversalDepth,
        ): Refinement<TraversalFrontierEntry, TraversalFrontierEntryFailure> =
            when {
                node.endpoint.lease != plan.start.lease ->
                    Refinement.Rejected(TraversalFrontierEntryFailure.LEASE_MISMATCH)
                !plan.admitsEndpoint(node.endpoint) -> Refinement.Rejected(TraversalFrontierEntryFailure.SCOPE_MISMATCH)
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
data class TraversalPendingRead
private constructor(
    val entry: TraversalFrontierEntry,
    val relationContinuation: RelationContinuation,
) {
    companion object {
        /**
         * Proof transition: `(TraversalPlan, TraversalFrontierEntry, RelationContinuation) ->
         * Refinement<TraversalPendingRead, TraversalPendingReadFailure>`.
         *
         * Establishes that incomplete one-hop work resumes the same exact node, meaning, and generation inside the plan
         * scope. [TraversalPendingReadFailure] is the closed expected failure. Raw relation continuation decoding may
         * occur only before this boundary.
         */
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            continuation: RelationContinuation,
        ): Refinement<TraversalPendingRead, TraversalPendingReadFailure> =
            when {
                entry.node.endpoint.lease != plan.start.lease || !plan.admitsEndpoint(entry.node.endpoint) ->
                    Refinement.Rejected(TraversalPendingReadFailure.FRONTIER_MISMATCH)
                continuation.subject != entry.node.fingerprint ->
                    Refinement.Rejected(TraversalPendingReadFailure.SELECTOR_MISMATCH)
                continuation.meaning != plan.meaning ->
                    Refinement.Rejected(TraversalPendingReadFailure.MEANING_MISMATCH)
                continuation.scope != RelationScopeFingerprint.from(entry.node.endpoint, plan.expansion) ->
                    Refinement.Rejected(TraversalPendingReadFailure.SCOPE_MISMATCH)
                continuation.authority != plan.start.lease.identity ->
                    Refinement.Rejected(TraversalPendingReadFailure.GENERATION_MISMATCH)
                else -> Refinement.Refined(TraversalPendingRead(entry, continuation))
            }
    }
}

sealed interface TraversalPendingState {
    data object None : TraversalPendingState

    @ConsistentCopyVisibility
    data class Active internal constructor(val read: TraversalPendingRead) : TraversalPendingState

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
    RETAINED_OMISSION_MISMATCH,
}

/** Detached deterministic traversal state carried by a qualified continuation. */
class TraversalCheckpoint
private constructor(
    val identity: TraversalIdentityFingerprint,
    val frontier: List<TraversalFrontierEntry>,
    val visited: Set<RelationEndpointFingerprint>,
    val pending: TraversalPendingState,
    val terminalRelationLimitations: Set<RelationLimitation>,
    val progress: TraversalProgress,
    val retainedOmissions: List<TraversalPartialExpansion>,
) {
    val retainedBytes: Long =
        (frontier.sumOf { it.toString().toByteArray(StandardCharsets.UTF_8).size.toLong() } +
            visited.sumOf { it.value.length.toLong() } +
            pending.toString().toByteArray(StandardCharsets.UTF_8).size.toLong() +
            retainedOmissions.sumOf { it.toString().toByteArray(StandardCharsets.UTF_8).size.toLong() }) * 4L + 512L

    companion object {
        /**
         * Proof transition: `TraversalPlan -> TraversalCheckpoint`.
         *
         * Establishes the exact unvisited depth-zero start frontier with no hidden prior work.
         */
        fun initial(plan: TraversalPlan): TraversalCheckpoint =
            TraversalCheckpoint(
                identity = plan.identity,
                frontier = Collections.unmodifiableList(listOf(TraversalFrontierEntry.initial(plan))),
                visited = emptySet(),
                pending = TraversalPendingState.None,
                terminalRelationLimitations = emptySet(),
                progress = TraversalProgress.Initial,
                retainedOmissions = emptyList(),
            )

        /**
         * Proof transition: `(TraversalPlan, frontier, visited, pending) -> Refinement<TraversalCheckpoint,
         * TraversalCheckpointFailure>`.
         *
         * Establishes exact plan identity, deterministic unique frontier order, scope/lease retention, cycle state, and
         * a pending read owned by one visited node. [TraversalCheckpointFailure] is the closed expected failure. Raw
         * collections may enter only from the pure traversal engine or continuation transport.
         */
        fun create(
            plan: TraversalPlan,
            frontier: List<TraversalFrontierEntry>,
            visited: Set<RelationEndpointFingerprint>,
            pending: TraversalPendingState,
            terminalRelationLimitations: Set<RelationLimitation> = emptySet(),
            progress: TraversalProgress = TraversalProgress.Initial,
            retainedOmissions: List<TraversalPartialExpansion> = emptyList(),
        ): Refinement<TraversalCheckpoint, TraversalCheckpointFailure> {
            if (frontier != frontier.sorted()) {
                return Refinement.Rejected(TraversalCheckpointFailure.NON_DETERMINISTIC_FRONTIER)
            }
            if (frontier.map { it.node.fingerprint }.distinct().size != frontier.size) {
                return Refinement.Rejected(TraversalCheckpointFailure.DUPLICATE_FRONTIER_NODE)
            }
            if (
                frontier.any {
                    it.node.endpoint.lease != plan.start.lease || !plan.admitsEndpoint(it.node.endpoint)
                }
            ) {
                return Refinement.Rejected(TraversalCheckpointFailure.FRONTIER_NODE_MISMATCH)
            }
            val frontierIds = frontier.mapTo(linkedSetOf()) { it.node.fingerprint }
            if (frontierIds.any(visited::contains)) {
                return Refinement.Rejected(TraversalCheckpointFailure.VISITED_FRONTIER_OVERLAP)
            }
            if (pending is TraversalPendingState.Active && pending.read.entry.node.fingerprint !in visited) {
                return Refinement.Rejected(TraversalCheckpointFailure.PENDING_NODE_NOT_VISITED)
            }
            if (pending is TraversalPendingState.Active && pending.read.entry.node.fingerprint in frontierIds) {
                return Refinement.Rejected(TraversalCheckpointFailure.PENDING_NODE_IN_FRONTIER)
            }
            when (val admitted = admitRetainedOmissions(plan, retainedOmissions, terminalRelationLimitations)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(
                TraversalCheckpoint(
                    plan.identity,
                    Collections.unmodifiableList(ArrayList(frontier)),
                    Collections.unmodifiableSet(LinkedHashSet(visited)),
                    pending,
                    Collections.unmodifiableSet(LinkedHashSet(terminalRelationLimitations.sortedBy { it.ordinal })),
                    progress,
                    Collections.unmodifiableList(retainedOmissions.distinct().toList()),
                )
            )
        }

        private fun admitRetainedOmissions(
            plan: TraversalPlan,
            omissions: List<TraversalPartialExpansion>,
            terminal: Set<RelationLimitation>,
        ): Refinement<Unit, TraversalCheckpointFailure> {
            if (omissions.distinct().size != omissions.size)
                return Refinement.Rejected(TraversalCheckpointFailure.RETAINED_OMISSION_MISMATCH)
            if (
                omissions.any {
                    it.entry.node.endpoint.lease != plan.start.lease || !plan.admitsEndpoint(it.entry.node.endpoint)
                }
            )
                return Refinement.Rejected(TraversalCheckpointFailure.RETAINED_OMISSION_MISMATCH)
            if (omissions.any { !plan.budget.extent.permitsExpansion(it.entry.depth) })
                return Refinement.Rejected(TraversalCheckpointFailure.RETAINED_OMISSION_MISMATCH)
            if (omissions.any { it.omissions.none { evidence -> evidence.reason in terminal } })
                return Refinement.Rejected(TraversalCheckpointFailure.RETAINED_OMISSION_MISMATCH)
            return Refinement.Refined(Unit)
        }
    }
}
