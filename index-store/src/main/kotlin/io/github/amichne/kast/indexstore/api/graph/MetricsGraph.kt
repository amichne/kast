package io.github.amichne.kast.indexstore.api.graph

import kotlinx.serialization.Serializable

@Serializable
data class MetricsGraph(
    val focalNodeId: String,
    val nodes: List<MetricsGraphNode>,
    val edges: List<MetricsGraphEdge>,
    val index: MetricsGraphIndex,
)
