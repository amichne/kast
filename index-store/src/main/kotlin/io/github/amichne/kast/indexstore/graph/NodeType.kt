package io.github.amichne.kast.indexstore.graph

import io.github.amichne.kast.indexstore.api.graph.MetricsGraphNodeType

internal enum class NodeType {
    SYMBOL,
    FILE,
    REFERENCE_EDGE,
}

internal fun NodeType.toApi(): MetricsGraphNodeType =
    when (this) {
        NodeType.SYMBOL -> MetricsGraphNodeType.SYMBOL
        NodeType.FILE -> MetricsGraphNodeType.FILE
        NodeType.REFERENCE_EDGE -> MetricsGraphNodeType.REFERENCE_EDGE
    }
