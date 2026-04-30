package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class FanInMetric(
    val targetFqName: String,
    val targetPath: String?,
    val targetModulePath: String?,
    val targetSourceSet: String?,
    val occurrenceCount: Int,
    val sourceFileCount: Int,
    val sourceModuleCount: Int,
)

@Serializable
data class FanOutMetric(
    val sourcePath: String,
    val sourceModulePath: String?,
    val sourceSourceSet: String?,
    val occurrenceCount: Int,
    val targetSymbolCount: Int,
    val targetFileCount: Int,
    val targetModuleCount: Int,
    val externalTargetCount: Int,
)

@Serializable
data class ModuleCouplingMetric(
    val sourceModulePath: String,
    val sourceSourceSet: String?,
    val targetModulePath: String,
    val targetSourceSet: String?,
    val referenceCount: Int,
)

@Serializable
data class DeadCodeCandidate(
    val identifier: String,
    val path: String,
    val modulePath: String?,
    val sourceSet: String?,
    val packageName: String?,
    val confidence: MetricsConfidence,
    val reason: String,
)

@Serializable
data class ChangeImpactNode(
    val sourcePath: String,
    val depth: Int,
    val viaTargetFqName: String,
    val occurrenceCount: Int,
    val semantics: ImpactSemantics,
)

@Serializable
data class MetricsGraph(
    val focalNodeId: String,
    val nodes: List<MetricsGraphNode>,
    val edges: List<MetricsGraphEdge>,
    val index: MetricsGraphIndex,
)

@Serializable
data class MetricsGraphNode(
    val id: String,
    val name: String,
    val type: MetricsGraphNodeType,
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val attributes: List<String> = emptyList(),
)

@Serializable
data class MetricsGraphEdge(
    val from: String,
    val to: String,
    val edgeType: MetricsGraphEdgeType,
    val weight: Int = 1,
)

@Serializable
data class MetricsGraphIndex(
    val symbolCount: Int,
    val fileCount: Int,
    val referenceCount: Int,
    val maxDepth: Int,
)

@Serializable
enum class MetricsGraphNodeType {
    SYMBOL,
    FILE,
    REFERENCE_EDGE,
}

@Serializable
enum class MetricsGraphEdgeType {
    CONTAINS,
    REFERENCED_BY,
    REFERENCES,
}

@Serializable
enum class MetricsConfidence {
    LOW,
}

@Serializable
enum class ImpactSemantics {
    FILE_LEVEL_APPROXIMATION,
}
