package io.github.amichne.kast.indexstore.api.graph

import kotlinx.serialization.Serializable

@Serializable
enum class MetricsGraphEdgeType {
    CONTAINS,
    REFERENCED_BY,
    REFERENCES,
}
