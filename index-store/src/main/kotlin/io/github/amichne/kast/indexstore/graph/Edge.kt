package io.github.amichne.kast.indexstore.graph

import io.github.amichne.kast.indexstore.api.graph.MetricsGraphEdge

internal data class Edge(
    val from: String,
    val to: String,
    val edgeType: EdgeType,
    val weight: Int = 1,
)

internal fun Edge.toApi(): MetricsGraphEdge =
    MetricsGraphEdge(
        from = from,
        to = to,
        edgeType = edgeType.toApi(),
        weight = weight,
    )
