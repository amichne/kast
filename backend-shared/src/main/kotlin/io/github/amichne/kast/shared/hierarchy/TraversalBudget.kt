package io.github.amichne.kast.shared.hierarchy

import io.github.amichne.kast.api.CallHierarchyStats

/**
 * Tracks resource budgets and accumulated statistics during a single
 * call hierarchy traversal. Create a fresh instance per request.
 */
class TraversalBudget(
    val maxTotalCalls: Int,
    val maxChildrenPerNode: Int,
    timeoutMillis: Long,
) {
    private val startedAtNanos = System.nanoTime()
    private val timeoutNanos = timeoutMillis * 1_000_000
    private val visitedFiles = linkedSetOf<String>()

    var totalNodes: Int = 0
        private set
    var totalEdges: Int = 0
        private set
    var truncatedNodes: Int = 0
        private set
    var maxDepthReached: Int = 0
        private set
    var timeoutHit: Boolean = false
    var maxTotalCallsHit: Boolean = false
    var maxChildrenHit: Boolean = false

    fun visitFile(filePath: String) {
        visitedFiles += filePath
    }

    fun recordNode(depth: Int) {
        totalNodes += 1
        if (depth > maxDepthReached) {
            maxDepthReached = depth
        }
    }

    fun recordEdge() {
        totalEdges += 1
    }

    fun recordTruncation() {
        truncatedNodes += 1
    }

    fun timeoutReached(): Boolean {
        if (timeoutHit) {
            return true
        }
        if (System.nanoTime() - startedAtNanos >= timeoutNanos) {
            timeoutHit = true
        }
        return timeoutHit
    }

    fun toStats(): CallHierarchyStats = CallHierarchyStats(
        totalNodes = totalNodes,
        totalEdges = totalEdges,
        truncatedNodes = truncatedNodes,
        maxDepthReached = maxDepthReached,
        timeoutReached = timeoutHit,
        maxTotalCallsReached = maxTotalCallsHit,
        maxChildrenPerNodeReached = maxChildrenHit,
        filesVisited = visitedFiles.size,
    )
}
