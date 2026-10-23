package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.relation.contract.StaticCallbackNode
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import java.util.Collections

/** One graph edge, not one named caller hop. Depth is the shortest distance from the lexical supplier. */
data class CallbackTraversalStep internal constructor(val edge: StaticCallbackEdge, val depth: TraversalDepth)

/**
 * A breadth-first navigation of already admitted static proof. This performs no semantic reads and creates no named
 * relation facts. An anonymous body is reached only through invocation evidence, never through containment or supply
 * alone. A depth cut retains the exact unexpanded edges; unsupported edge families retain the established prefix.
 */
class CallbackTraversal
private constructor(
    val root: CompilerGroundedSymbolEvidence,
    val extent: TraversalExtent,
    val steps: List<CallbackTraversalStep>,
    val frontier: List<StaticCallbackEdge>,
    val unsupported: List<StaticCallbackEdge>,
) {
    companion object {
        fun walk(
            graph: CompleteStaticCallbackGraph,
            extent: TraversalExtent = TraversalExtent.Exhaustive,
        ): CallbackTraversal = Navigation(graph.observation.lexicalOwner, graph.edges, extent).drain()

        fun walk(
            graph: CompleteCallbackFlowGraph,
            extent: TraversalExtent = TraversalExtent.Exhaustive,
        ): CallbackTraversal = Navigation(graph.root, graph.edges, extent).drain()
    }

    private class Navigation(
        private val root: CompilerGroundedSymbolEvidence,
        admittedEdges: List<StaticCallbackEdge>,
        private val extent: TraversalExtent,
    ) {
        private val outgoing = admittedEdges.groupBy { it.source }
        private val roots =
            outgoing.keys.filterIsInstance<StaticCallbackNode.Named>().filter {
                it.callable.evidence == root
            }
        private val pending =
            ArrayDeque<Pair<StaticCallbackNode, TraversalDepth>>().apply {
                roots.forEach { add(it to TraversalDepth.Zero) }
            }
        private val visited = roots.toMutableSet<StaticCallbackNode>()
        private val steps = mutableListOf<CallbackTraversalStep>()
        private val frontier = mutableListOf<StaticCallbackEdge>()
        private val unsupported = mutableListOf<StaticCallbackEdge>()

        fun drain(): CallbackTraversal {
            while (pending.isNotEmpty()) {
                val (source, depth) = pending.removeFirst()
                expand(source, depth)
            }
            return CallbackTraversal(
                root,
                extent,
                Collections.unmodifiableList(steps),
                Collections.unmodifiableList(frontier),
                Collections.unmodifiableList(unsupported),
            )
        }

        private fun expand(source: StaticCallbackNode, depth: TraversalDepth) {
            val edges = outgoing[source].orEmpty()
            if (extent.permitsExpansion(depth)) {
                when (val next = depth.next()) {
                    is Refinement.Refined -> edges.forEach { follow(it, next.value) }
                    is Refinement.Rejected -> frontier.addAll(edges)
                }
            } else frontier.addAll(edges)
        }

        private fun follow(edge: StaticCallbackEdge, depth: TraversalDepth) {
            when (edge) {
                is StaticCallbackEdge.Supply,
                is StaticCallbackEdge.Forward,
                is StaticCallbackEdge.Invoke,
                is StaticCallbackEdge.DirectInvoke,
                is StaticCallbackEdge.NestedDirectInvoke,
                is StaticCallbackEdge.BodyTarget -> {
                    steps.add(CallbackTraversalStep(edge, depth))
                    if (visited.add(edge.target)) pending.add(edge.target to depth)
                }
                is StaticCallbackEdge.ImmutableUses,
                is StaticCallbackEdge.DependencyContractInvoke,
                is StaticCallbackEdge.DependencyDeferredSupply,
                is StaticCallbackEdge.BodyClassReference -> unsupported.add(edge)
            }
        }
    }
}
