package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class MetricsGraphEdge(
    val from: String,
    val to: String,
    val edgeType: MetricsGraphEdgeType,
    val weight: Int = 1,
)
