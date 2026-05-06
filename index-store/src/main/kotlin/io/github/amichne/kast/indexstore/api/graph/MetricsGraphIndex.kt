package io.github.amichne.kast.indexstore.api.graph

import kotlinx.serialization.Serializable

@Serializable
data class MetricsGraphIndex(
    val symbolCount: Int,
    val fileCount: Int,
    val referenceCount: Int,
    val maxDepth: Int,
)
