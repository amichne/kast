package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.workspace.contract.GradleProjectIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName

sealed interface TopologyGraphOpen {
    data class Opened(val graph: TopologyGraph) : TopologyGraphOpen
    data class Rejected(val failure: TopologySnapshotReadFailure) : TopologyGraphOpen
}

/** Read-only repository graph reconstructed from one re-admitted published SQLite snapshot. */
fun interface TopologyGraphOperations {
    /**
     * Proof transition: `PublishedTopologySnapshot -> TopologyGraphOpen`.
     *
     * [TopologyGraphOpen.Opened] establishes that every detached row passed snapshot identity,
     * coverage, edge-closure, cardinality, and digest admission before graph construction. Expected
     * persistence failures remain [TopologySnapshotReadFailure]. Raw rows may enter only through
     * the snapshot content reader implemented by the SQLite adapter.
     */
    fun open(snapshot: PublishedTopologySnapshot): TopologyGraphOpen
}

/** One deterministic breadth-first visit from an exact compiler symbol identity. */
interface TopologyVisit {
    val symbol: TopologySymbol
    val depth: TopologyHopDepth
}

enum class TopologyHopDepthFailure {
    OVERFLOW,
}

/** Finite non-negative hop depth derived during topology traversal. */
@JvmInline
value class TopologyHopDepth private constructor(val value: Int) {
    companion object {
        val Zero: TopologyHopDepth = TopologyHopDepth(0)
    }

    /**
     * Proof transition: `TopologyHopDepth -> Refinement<TopologyHopDepth,
     * TopologyHopDepthFailure>`.
     *
     * Establishes the next representable graph hop. [TopologyHopDepthFailure] is the closed
     * expected overflow state. Raw depth extraction is permitted only in result presentation.
     */
    fun next(): Refinement<TopologyHopDepth, TopologyHopDepthFailure> =
        if (value == Int.MAX_VALUE) Refinement.Rejected(TopologyHopDepthFailure.OVERFLOW)
        else Refinement.Refined(TopologyHopDepth(value + 1))
}

/** Complete deterministic breadth-first traversal of the snapshot-reachable subgraph. */
interface TopologyTraversal {
    val visits: List<TopologyVisit>
}

sealed interface TopologyGraphTraversal {
    data class Traversed(val result: TopologyTraversal) : TopologyGraphTraversal
    data object UnknownStart : TopologyGraphTraversal
    data object DepthOverflow : TopologyGraphTraversal
}

/** One shortest directed path, retaining its exact ordered symbols and edge evidence. */
interface TopologyPath {
    val symbols: List<TopologySymbol>
    val edges: List<TopologyEdge>
}

sealed interface TopologyReachability {
    data class Reachable(val path: TopologyPath) : TopologyReachability
    data object Unreachable : TopologyReachability
    data object UnknownEndpoint : TopologyReachability
}

/** One elementary directed cycle in canonical rotation. */
interface TopologyCycle {
    val symbols: List<TopologySymbol>
    val edges: List<TopologyEdge>
}

/** One maximal strongly connected component. */
interface TopologyStrongComponent {
    val symbols: List<TopologySymbol>
}

/** One edge between distinct strongly connected components. */
interface TopologyCondensationEdge {
    val source: TopologyStrongComponent
    val target: TopologyStrongComponent
}

/** Deterministic topological order and edge set of the acyclic SCC condensation graph. */
interface TopologyCondensation {
    val order: List<TopologyStrongComponent>
    val edges: List<TopologyCondensationEdge>
}

enum class TopologyQuotientLevel {
    FILE,
    PROJECT,
    SOURCE_SET,
}

/** Closed repository ownership identity used by quotient graphs. */
sealed interface TopologyQuotientNode {
    data class File(val path: WorkspaceSourcePath) : TopologyQuotientNode

    data class Project(val project: GradleProjectIdentity) : TopologyQuotientNode

    data class SourceSet(
        val project: GradleProjectIdentity,
        val sourceSet: WorkspaceSourceSetName,
    ) : TopologyQuotientNode
}

/** One aggregated directed edge between distinct quotient nodes. */
interface TopologyQuotientEdge {
    val source: TopologyQuotientNode
    val target: TopologyQuotientNode
    val kinds: Set<TopologyEdgeKind>
}

/** Deterministic quotient graph at one explicit ownership level. */
interface TopologyQuotientGraph {
    val level: TopologyQuotientLevel
    val nodes: List<TopologyQuotientNode>
    val edges: List<TopologyQuotientEdge>
}

/** Complete read capability for one immutable topology snapshot. */
interface TopologyGraph {
    val snapshot: PublishedTopologySnapshot

    fun traverse(start: CompilerSymbolIdentity): TopologyGraphTraversal

    fun reachability(
        source: CompilerSymbolIdentity,
        target: CompilerSymbolIdentity,
    ): TopologyReachability

    fun cycles(): List<TopologyCycle>

    fun stronglyConnectedComponents(): List<TopologyStrongComponent>

    fun condensation(): TopologyCondensation

    fun quotient(level: TopologyQuotientLevel): TopologyQuotientGraph

    /** Canonical byte-equivalence projection of the detached symbol and edge graph. */
    fun canonicalProjection(): String
}
