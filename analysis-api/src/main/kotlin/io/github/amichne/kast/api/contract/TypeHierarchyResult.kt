@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class TypeHierarchyResult(
    @DocField(description = "Root node of the type hierarchy tree.")
    val root: TypeHierarchyNode,
    @DocField(description = "Traversal statistics including truncation indicators.")
    val stats: TypeHierarchyStats,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class TypeHierarchyNode(
    @DocField(description = "The class or interface at this node in the hierarchy.")
    val symbol: Symbol,
    @DocField(description = "Child nodes (subtypes or supertypes depending on direction).")
    val children: List<TypeHierarchyNode>,
    @DocField(description = "Present when this node's subtree was truncated.")
    val truncation: TypeHierarchyTruncation? = null,
)

@Serializable
data class TypeHierarchyStats(
    @DocField(description = "Total number of nodes in the returned tree.")
    val totalNodes: Int,
    @DocField(description = "Deepest level reached during traversal.")
    val maxDepthReached: Int,
    @DocField(description = "True if results were truncated due to bounds.")
    val truncated: Boolean,
)

@Serializable
data class TypeHierarchyTruncation(
    @DocField(description = "Why this node's subtree was truncated (CYCLE or MAX_RESULTS).")
    val reason: TypeHierarchyTruncationReason,
    @DocField(description = "Human-readable details about the truncation.")
    val details: String? = null,
)

@Serializable
enum class TypeHierarchyTruncationReason {
    CYCLE,
    MAX_RESULTS,
}
