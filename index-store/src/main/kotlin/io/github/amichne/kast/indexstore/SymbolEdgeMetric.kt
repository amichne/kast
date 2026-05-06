package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class SymbolEdgeMetric(
    val sourceFqName: String?,
    val targetFqName: String,
    val edgeKind: String,
    val sourcePath: String,
    val targetPath: String?,
    val count: Int,
)
