package io.github.amichne.kast.indexstore.graph

import io.github.amichne.kast.indexstore.api.graph.MetricsGraphEdgeType

internal enum class EdgeType {
    CONTAINS,
    REFERENCED_BY,
    REFERENCES,
}

internal fun EdgeType.toApi(): MetricsGraphEdgeType =
    when (this) {
        EdgeType.CONTAINS -> MetricsGraphEdgeType.CONTAINS
        EdgeType.REFERENCED_BY -> MetricsGraphEdgeType.REFERENCED_BY
        EdgeType.REFERENCES -> MetricsGraphEdgeType.REFERENCES
    }
