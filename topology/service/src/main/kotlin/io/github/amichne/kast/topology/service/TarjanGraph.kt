package io.github.amichne.kast.topology.service

import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologySymbol

internal class TarjanGraph(
    private val symbols: List<TopologySymbol>,
    private val outgoing: Map<CompilerSymbolIdentity, List<TopologyEdge>>,
) {
    private var nextIndex = 0
    private val indices = mutableMapOf<CompilerSymbolIdentity, Int>()
    private val lowLinks = mutableMapOf<CompilerSymbolIdentity, Int>()
    private val stack = ArrayDeque<TopologySymbol>()
    private val onStack = mutableSetOf<CompilerSymbolIdentity>()
    private val components = mutableListOf<GraphStrongComponent>()

    fun run(): List<GraphStrongComponent> {
        symbols.forEach { symbol ->
            if (identity(symbol) !in indices) connect(symbol)
        }
        return components.sortedBy { it.key.value }
    }

    private fun connect(symbol: TopologySymbol) {
        val symbolIdentity = identity(symbol)
        indices[symbolIdentity] = nextIndex
        lowLinks[symbolIdentity] = nextIndex
        nextIndex += 1
        stack += symbol
        onStack += symbolIdentity

        outgoing.getOrElse(symbolIdentity, ::emptyList).forEach { edge ->
            val targetIdentity = identity(edge.target)
            if (targetIdentity !in indices) {
                connect(edge.target)
                lowLinks[symbolIdentity] = minOf(
                    lowLinks.getValue(symbolIdentity),
                    lowLinks.getValue(targetIdentity),
                )
            } else if (targetIdentity in onStack) {
                lowLinks[symbolIdentity] = minOf(
                    lowLinks.getValue(symbolIdentity),
                    indices.getValue(targetIdentity),
                )
            }
        }

        if (lowLinks.getValue(symbolIdentity) == indices.getValue(symbolIdentity)) {
            val component = mutableListOf<TopologySymbol>()
            do {
                val member = stack.removeLast()
                onStack.remove(identity(member))
                component += member
            } while (member != symbol)
            components += GraphStrongComponent(component.sortedBy { identity(it).value })
        }
    }
}
