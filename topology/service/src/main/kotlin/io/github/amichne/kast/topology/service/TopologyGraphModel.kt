package io.github.amichne.kast.topology.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.GradleProjectIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName

internal sealed interface TopologyGraphOpen {
    data class Opened(val graph: TopologyGraph) : TopologyGraphOpen
    data class Rejected(val failure: TopologySnapshotReadFailure) : TopologyGraphOpen
}

/** Service-internal read-only graph reconstructed from one re-admitted SQLite snapshot. */
internal fun interface TopologyGraphOperations {
    fun open(snapshot: PublishedTopologySnapshot): TopologyGraphOpen
}

internal interface TopologyVisit {
    val symbol: TopologySymbol
    val depth: TopologyHopDepth
}

internal enum class TopologyHopDepthFailure {
    OVERFLOW,
}

@JvmInline
internal value class TopologyHopDepth private constructor(val value: Int) {
    companion object {
        val Zero: TopologyHopDepth = TopologyHopDepth(0)
    }

    /**
     * Proof transition: `TopologyHopDepth -> Refinement<TopologyHopDepth,
     * TopologyHopDepthFailure>`.
     *
     * Establishes the next representable internal graph hop. [TopologyHopDepthFailure] is the
     * closed expected overflow state. Raw depth extraction is permitted only in tests.
     */
    fun next(): Refinement<TopologyHopDepth, TopologyHopDepthFailure> =
        if (value == Int.MAX_VALUE) Refinement.Rejected(TopologyHopDepthFailure.OVERFLOW)
        else Refinement.Refined(TopologyHopDepth(value + 1))
}

internal interface TopologyTraversal {
    val visits: List<TopologyVisit>
}

internal sealed interface TopologyGraphTraversal {
    data class Traversed(val result: TopologyTraversal) : TopologyGraphTraversal
    data object UnknownStart : TopologyGraphTraversal
    data object DepthOverflow : TopologyGraphTraversal
}

internal interface TopologyPath {
    val symbols: List<TopologySymbol>
    val edges: List<TopologyEdge>
}

internal sealed interface TopologyReachability {
    data class Reachable(val path: TopologyPath) : TopologyReachability
    data object Unreachable : TopologyReachability
    data object UnknownEndpoint : TopologyReachability
}

internal interface TopologyCycle {
    val symbols: List<TopologySymbol>
    val edges: List<TopologyEdge>
}

internal interface TopologyStrongComponent {
    val symbols: List<TopologySymbol>
}

internal interface TopologyCondensationEdge {
    val source: TopologyStrongComponent
    val target: TopologyStrongComponent
}

internal interface TopologyCondensation {
    val order: List<TopologyStrongComponent>
    val edges: List<TopologyCondensationEdge>
}

internal enum class TopologyQuotientLevel {
    FILE,
    PROJECT,
    SOURCE_SET,
}

internal sealed interface TopologyQuotientNode {
    data class File(val path: WorkspaceSourcePath) : TopologyQuotientNode
    data class Project(val project: GradleProjectIdentity) : TopologyQuotientNode
    data class SourceSet(
        val project: GradleProjectIdentity,
        val sourceSet: WorkspaceSourceSetName,
    ) : TopologyQuotientNode
}

internal interface TopologyQuotientEdge {
    val source: TopologyQuotientNode
    val target: TopologyQuotientNode
    val kinds: Set<TopologyEdgeKind>
}

internal interface TopologyQuotientGraph {
    val level: TopologyQuotientLevel
    val nodes: List<TopologyQuotientNode>
    val edges: List<TopologyQuotientEdge>
}

internal interface TopologyGraph {
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

    fun canonicalProjection(): String
}
