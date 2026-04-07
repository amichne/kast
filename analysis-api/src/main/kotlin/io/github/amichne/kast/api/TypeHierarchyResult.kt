package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class TypeHierarchyResult(
    val root: TypeHierarchyNode,
    val stats: TypeHierarchyStats,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class TypeHierarchyNode(
    val symbol: Symbol,
    val children: List<TypeHierarchyNode>,
    val truncation: TypeHierarchyTruncation? = null,
)

@Serializable
data class TypeHierarchyStats(
    val totalNodes: Int,
    val maxDepthReached: Int,
    val truncated: Boolean,
)

@Serializable
data class TypeHierarchyTruncation(
    val reason: TypeHierarchyTruncationReason,
    val details: String? = null,
)

@Serializable
enum class TypeHierarchyTruncationReason {
    CYCLE,
    MAX_RESULTS,
}
