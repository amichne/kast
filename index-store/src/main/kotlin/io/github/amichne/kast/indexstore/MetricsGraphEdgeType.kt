package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
enum class MetricsGraphEdgeType {
    CONTAINS,
    REFERENCED_BY,
    REFERENCES,
}
