@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyResult(
    @DocField(description = "Root node of the call hierarchy tree.")
    val root: CallNode,
    @DocField(description = "Traversal statistics including truncation indicators.")
    val stats: CallHierarchyStats,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class CallHierarchyStats(
    @DocField(description = "Total number of nodes in the returned tree.")
    val totalNodes: Int,
    @DocField(description = "Total number of caller/callee edges traversed.")
    val totalEdges: Int,
    @DocField(description = "Number of nodes that were truncated due to bounds.")
    val truncatedNodes: Int,
    @DocField(description = "Deepest level reached during traversal.")
    val maxDepthReached: Int,
    @DocField(description = "True if the traversal was stopped by the timeout.")
    val timeoutReached: Boolean,
    @DocField(description = "True if the total node limit was hit.")
    val maxTotalCallsReached: Boolean,
    @DocField(description = "True if any node hit the per-node child limit.")
    val maxChildrenPerNodeReached: Boolean,
    @DocField(description = "Number of unique source files examined.")
    val filesVisited: Int,
)
