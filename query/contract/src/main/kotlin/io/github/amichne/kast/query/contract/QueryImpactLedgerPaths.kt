package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.ValueSite
import java.util.Collections
import java.util.IdentityHashMap

/** Every prefix keeps its exact producer, origin histories, and preceding steps. */
internal class QueryImpactLedgerPaths(val all: List<QueryImpactPath>) {
    private val producers = all.groupBy { it.producer }
    private val positions = IdentityHashMap<QueryImpactPath, List<Prefix>>()

    init {
        val roots = linkedMapOf<Origin, Node>()
        val routes = IdentityHashMap<QueryImpactPath, List<Node>>()
        for (path in all) {
            var current = roots.getOrPut(Origin(path.producer, path.origins())) { Node() }
            val route = mutableListOf(current)
            current.paths += path
            for (step in path.steps) {
                current = current.children.getOrPut(step) { Node() }
                current.paths += path
                route += current
            }
            current.terminals += path.terminal
            routes[path] = route
        }
        val frozen = IdentityHashMap<Node, Prefix>()
        for ((path, route) in routes) {
            positions[path] = route.map { node ->
                frozen.getOrPut(node) {
                    Prefix(
                        Collections.unmodifiableList(node.paths.toList()),
                        Collections.unmodifiableSet(node.children.keys.toSet()),
                        Collections.unmodifiableSet(node.terminals.toSet()),
                    )
                }
            }
        }
    }

    fun from(producer: ValueSite): List<QueryImpactPath> = producers[producer].orEmpty()

    fun at(path: QueryImpactPath, index: Int): Prefix = positions.getValue(path)[index]

    class Prefix
    internal constructor(
        val paths: List<QueryImpactPath>,
        val steps: Set<QueryImpactStep>,
        val terminals: Set<QueryImpactTerminal>,
    )

    private data class Origin(val producer: ValueSite, val histories: Set<RepresentationHistory.Origin>)

    private class Node {
        val paths = mutableListOf<QueryImpactPath>()
        val children = linkedMapOf<QueryImpactStep, Node>()
        val terminals = linkedSetOf<QueryImpactTerminal>()
    }
}
