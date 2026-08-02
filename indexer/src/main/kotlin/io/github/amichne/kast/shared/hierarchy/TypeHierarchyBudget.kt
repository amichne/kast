package io.github.amichne.kast.shared.hierarchy

import io.github.amichne.kast.api.contract.result.TypeHierarchyStats

/** Budget tracker for type hierarchy traversal. */
class TypeHierarchyBudget(
    val maxResults: Int,
) {
    var totalNodes: Int = 0
        private set
    var maxDepthReached: Int = 0
        private set
    var truncated: Boolean = false
        private set

    fun recordNode(depth: Int) {
        totalNodes += 1
        if (depth > maxDepthReached) {
            maxDepthReached = depth
        }
    }

    fun recordTruncation() {
        truncated = true
    }

    fun toStats() = TypeHierarchyStats(
        totalNodes = totalNodes,
        maxDepthReached = maxDepthReached,
        truncated = truncated,
    )
}
