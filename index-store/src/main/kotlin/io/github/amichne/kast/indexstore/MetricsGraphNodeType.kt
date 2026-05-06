package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
enum class MetricsGraphNodeType {
    SYMBOL,
    FILE,
    REFERENCE_EDGE,
}
