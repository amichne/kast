package io.github.amichne.kast.indexstore.graph

import io.github.amichne.kast.indexstore.api.graph.MetricsGraphIndex

internal data class Index(
    val symbolCount: Int,
    val fileCount: Int,
    val referenceCount: Int,
    val maxDepth: Int,
)

internal fun Index.toApi(): MetricsGraphIndex =
    MetricsGraphIndex(
        symbolCount = symbolCount,
        fileCount = fileCount,
        referenceCount = referenceCount,
        maxDepth = maxDepth,
    )
