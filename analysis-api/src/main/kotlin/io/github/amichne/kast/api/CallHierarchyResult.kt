package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyResult(
    val root: CallNode,
    val stats: CallHierarchyStats,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class CallHierarchyStats(
    val totalNodes: Int,
    val totalEdges: Int,
    val truncatedNodes: Int,
    val maxDepthReached: Int,
    val timeoutReached: Boolean,
    val maxTotalCallsReached: Boolean,
    val maxChildrenPerNodeReached: Boolean,
    val filesVisited: Int,
)
