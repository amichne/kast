package io.github.amichne.kast.topology.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySymbol
import java.util.PriorityQueue

internal class GraphIndex(content: TopologySnapshotContent) {
    private val symbols = content.symbols.sortedBy { identity(it).value }
    private val symbolByIdentity = symbols.associateBy(::identity)
    private val edges = content.edges.sortedWith(
        compareBy<TopologyEdge>({ identity(it.source).value }, { identity(it.target).value })
            .thenBy(TopologyEdge::canonicalProjection),
    )
    private val outgoing = edges.groupBy { identity(it.source) }
    private val ordinal = symbols.mapIndexed { index, symbol -> identity(symbol) to index }.toMap()

    fun traverse(start: CompilerSymbolIdentity): TopologyGraphTraversal {
        val first = symbolByIdentity[start] ?: return TopologyGraphTraversal.UnknownStart
        val visited = linkedSetOf(identity(first))
        val frontier = ArrayDeque<Pair<TopologySymbol, TopologyHopDepth>>()
        val visits = mutableListOf<GraphVisit>()
        frontier += first to TopologyHopDepth.Zero
        while (frontier.isNotEmpty()) {
            val (symbol, depth) = frontier.removeFirst()
            visits += GraphVisit(symbol, depth)
            for (edge in outgoing.getOrElse(identity(symbol), ::emptyList)) {
                if (visited.add(identity(edge.target))) {
                    val next = when (val advanced = depth.next()) {
                        is Refinement.Refined -> advanced.value
                        is Refinement.Rejected -> return TopologyGraphTraversal.DepthOverflow
                    }
                    frontier += edge.target to next
                }
            }
        }
        return TopologyGraphTraversal.Traversed(GraphTraversal(visits))
    }

    fun reachability(
        source: CompilerSymbolIdentity,
        target: CompilerSymbolIdentity,
    ): TopologyReachability {
        val sourceSymbol = symbolByIdentity[source]
            ?: return TopologyReachability.UnknownEndpoint
        val targetSymbol = symbolByIdentity[target]
            ?: return TopologyReachability.UnknownEndpoint
        if (source == target) return TopologyReachability.Reachable(
            GraphPath(listOf(sourceSymbol), emptyList()),
        )
        val frontier = ArrayDeque<TopologySymbol>()
        val predecessor = linkedMapOf<CompilerSymbolIdentity, TopologyEdge>()
        val visited = linkedSetOf(identity(sourceSymbol))
        frontier += sourceSymbol
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            outgoing.getOrElse(identity(current), ::emptyList).forEach { edge ->
                val next = identity(edge.target)
                if (visited.add(next)) {
                    predecessor[next] = edge
                    if (edge.target == targetSymbol) {
                        return TopologyReachability.Reachable(
                            shortestPath(sourceSymbol, targetSymbol, predecessor),
                        )
                    }
                    frontier += edge.target
                }
            }
        }
        return TopologyReachability.Unreachable
    }

    fun cycles(): List<TopologyCycle> {
        val cycles = linkedMapOf<List<TopologyEdge>, GraphCycle>()
        symbols.forEachIndexed { startOrdinal, start ->
            enumerateCycles(
                start,
                startOrdinal,
                start,
                linkedSetOf(identity(start)),
                mutableListOf(start),
                mutableListOf(),
                cycles,
            )
        }
        return cycles.values.sortedBy { cycle ->
            cycle.symbols.joinToString("\u0000") { identity(it).value }
        }
    }

    private fun enumerateCycles(
        start: TopologySymbol,
        startOrdinal: Int,
        current: TopologySymbol,
        visited: MutableSet<CompilerSymbolIdentity>,
        pathSymbols: MutableList<TopologySymbol>,
        pathEdges: MutableList<TopologyEdge>,
        cycles: MutableMap<List<TopologyEdge>, GraphCycle>,
    ) {
        outgoing.getOrElse(identity(current), ::emptyList).forEach { edge ->
            val nextIdentity = identity(edge.target)
            if (edge.target == start) {
                val cycleEdges = pathEdges + edge
                cycles.putIfAbsent(cycleEdges, GraphCycle(pathSymbols.toList(), cycleEdges))
            } else if (
                ordinal.getValue(nextIdentity) >= startOrdinal && visited.add(nextIdentity)
            ) {
                pathSymbols += edge.target
                pathEdges += edge
                enumerateCycles(
                    start,
                    startOrdinal,
                    edge.target,
                    visited,
                    pathSymbols,
                    pathEdges,
                    cycles,
                )
                pathSymbols.removeLast()
                pathEdges.removeLast()
                visited.remove(nextIdentity)
            }
        }
    }

    fun stronglyConnectedComponents(): List<TopologyStrongComponent> = components()

    fun condensation(): TopologyCondensation {
        val components = components()
        val componentByIdentity = components.flatMap { component ->
            component.symbols.map { identity(it) to component }
        }.toMap()
        val condensationEdges = edges.mapNotNull { edge ->
            val source = componentByIdentity.getValue(identity(edge.source))
            val target = componentByIdentity.getValue(identity(edge.target))
            if (source === target) null else source to target
        }.distinctBy { (source, target) -> source.key to target.key }
            .sortedWith(compareBy({ it.first.key.value }, { it.second.key.value }))
        val indegree = components.associateWith { 0 }.toMutableMap()
        condensationEdges.forEach { (_, target) -> indegree[target] = indegree.getValue(target) + 1 }
        val ready = PriorityQueue(compareBy<GraphStrongComponent> { it.key.value })
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val order = mutableListOf<GraphStrongComponent>()
        while (ready.isNotEmpty()) {
            val current = ready.remove()
            order += current
            condensationEdges.filter { it.first === current }.forEach { (_, target) ->
                indegree[target] = indegree.getValue(target) - 1
                if (indegree.getValue(target) == 0) ready += target
            }
        }
        return GraphCondensation(
            order,
            condensationEdges.map { (source, target) -> GraphCondensationEdge(source, target) },
        )
    }

    fun quotient(level: TopologyQuotientLevel): TopologyQuotientGraph {
        val nodeBySymbol = symbols.associate { symbol -> identity(symbol) to quotientNode(symbol, level) }
        val nodes = nodeBySymbol.values.distinct().sortedWith(QUOTIENT_NODE_ORDER)
        val grouped = edges.mapNotNull { edge ->
            val source = nodeBySymbol.getValue(identity(edge.source))
            val target = nodeBySymbol.getValue(identity(edge.target))
            if (source == target) null else (source to target) to edge.kind
        }.groupBy({ it.first }, { it.second })
        val quotientEdges = grouped.map { (pair, kinds) ->
            GraphQuotientEdge(pair.first, pair.second, kinds.toSortedSet())
        }.sortedWith(
            compareBy(QUOTIENT_NODE_ORDER, GraphQuotientEdge::source)
                .thenBy(QUOTIENT_NODE_ORDER, GraphQuotientEdge::target),
        )
        return GraphQuotient(level, nodes, quotientEdges)
    }

    fun canonicalProjection(): String = buildString {
        symbols.forEach { append("symbol:").append(it.canonicalProjection()).append('\n') }
        edges.forEach { append("edge:").append(it.canonicalProjection()).append('\n') }
    }

    private fun components(): List<GraphStrongComponent> = TarjanGraph(symbols, outgoing).run()

    private fun shortestPath(
        source: TopologySymbol,
        target: TopologySymbol,
        predecessor: Map<CompilerSymbolIdentity, TopologyEdge>,
    ): GraphPath {
        val reversedEdges = mutableListOf<TopologyEdge>()
        var cursor = target
        while (cursor != source) {
            val edge = predecessor.getValue(identity(cursor))
            reversedEdges += edge
            cursor = edge.source
        }
        val pathEdges = reversedEdges.asReversed()
        return GraphPath(listOf(source) + pathEdges.map(TopologyEdge::target), pathEdges)
    }
}

private data class GraphVisit(
    override val symbol: TopologySymbol,
    override val depth: TopologyHopDepth,
) : TopologyVisit

private data class GraphTraversal(override val visits: List<TopologyVisit>) : TopologyTraversal

private data class GraphPath(
    override val symbols: List<TopologySymbol>,
    override val edges: List<TopologyEdge>,
) : TopologyPath

private data class GraphCycle(
    override val symbols: List<TopologySymbol>,
    override val edges: List<TopologyEdge>,
) : TopologyCycle

internal data class GraphStrongComponent(
    override val symbols: List<TopologySymbol>,
) : TopologyStrongComponent {
    val key: CompilerSymbolIdentity = symbols.minBy { identity(it).value }.let(::identity)
}

private data class GraphCondensationEdge(
    override val source: TopologyStrongComponent,
    override val target: TopologyStrongComponent,
) : TopologyCondensationEdge

private data class GraphCondensation(
    override val order: List<TopologyStrongComponent>,
    override val edges: List<TopologyCondensationEdge>,
) : TopologyCondensation

private data class GraphQuotientEdge(
    override val source: TopologyQuotientNode,
    override val target: TopologyQuotientNode,
    override val kinds: Set<TopologyEdgeKind>,
) : TopologyQuotientEdge

private data class GraphQuotient(
    override val level: TopologyQuotientLevel,
    override val nodes: List<TopologyQuotientNode>,
    override val edges: List<TopologyQuotientEdge>,
) : TopologyQuotientGraph

private fun quotientNode(symbol: TopologySymbol, level: TopologyQuotientLevel): TopologyQuotientNode =
    when (level) {
        TopologyQuotientLevel.FILE -> TopologyQuotientNode.File(symbol.file.path)
        TopologyQuotientLevel.PROJECT -> TopologyQuotientNode.Project(symbol.file.sourceRoot.owner.project)
        TopologyQuotientLevel.SOURCE_SET -> TopologyQuotientNode.SourceSet(
            symbol.file.sourceRoot.owner.project,
            symbol.file.sourceRoot.owner.sourceSet,
        )
    }

private val QUOTIENT_NODE_ORDER = compareBy<TopologyQuotientNode>(
    { node ->
        when (node) {
            is TopologyQuotientNode.File -> 0
            is TopologyQuotientNode.Project -> 1
            is TopologyQuotientNode.SourceSet -> 2
        }
    },
    { node ->
        when (node) {
            is TopologyQuotientNode.File -> node.path.value
            is TopologyQuotientNode.Project -> node.project.buildRoot.value
            is TopologyQuotientNode.SourceSet -> node.project.buildRoot.value
        }
    },
    { node ->
        when (node) {
            is TopologyQuotientNode.File -> ""
            is TopologyQuotientNode.Project -> node.project.projectPath.value
            is TopologyQuotientNode.SourceSet -> node.project.projectPath.value
        }
    },
    { node ->
        when (node) {
            is TopologyQuotientNode.SourceSet -> node.sourceSet.value
            is TopologyQuotientNode.File,
            is TopologyQuotientNode.Project,
                -> ""
        }
    },
)

internal fun identity(symbol: TopologySymbol): CompilerSymbolIdentity =
    symbol.evidence.compilerIdentity
