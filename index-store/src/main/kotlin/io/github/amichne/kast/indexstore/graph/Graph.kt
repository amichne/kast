package io.github.amichne.kast.indexstore.graph

import io.github.amichne.kast.indexstore.api.graph.MetricsGraph

internal data class Graph(
    val focalNodeId: String,
    val nodes: List<Node>,
    val edges: List<Edge>,
    val index: Index,
)

internal fun Graph.toApi(): MetricsGraph =
    MetricsGraph(
        focalNodeId = focalNodeId,
        nodes = nodes.map(Node::toApi),
        edges = edges.map(Edge::toApi),
        index = index.toApi(),
    )
