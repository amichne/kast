package io.github.amichne.kast.shared.hierarchy

import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks resource budgets and accumulated statistics during a single
 * call hierarchy traversal. Create a fresh instance per request.
 *
 * All counters and flags are atomic so that concurrent child-node
 * expansion (via coroutines or parallel streams) is safe.
 */
class TraversalBudget(
    val maxTotalCalls: Int,
    val maxChildrenPerNode: Int,
    timeoutMillis: Long,
) {
    private val startedAtNanos = System.nanoTime()
    private val timeoutNanos = timeoutMillis * 1_000_000
    private val visitedFiles: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val _totalNodes = AtomicInteger(0)
    private val _totalEdges = AtomicInteger(0)
    private val _truncatedNodes = AtomicInteger(0)
    private val _maxDepthReached = AtomicInteger(0)

    val totalNodes: Int get() = _totalNodes.get()
    val totalEdges: Int get() = _totalEdges.get()
    val truncatedNodes: Int get() = _truncatedNodes.get()
    val maxDepthReached: Int get() = _maxDepthReached.get()

    val timeoutHit = AtomicBoolean(false)
    val maxTotalCallsHit = AtomicBoolean(false)
    val maxChildrenHit = AtomicBoolean(false)

    fun visitFile(filePath: String) {
        visitedFiles += filePath
    }

    fun recordNode(depth: Int) {
        _totalNodes.incrementAndGet()
        _maxDepthReached.updateAndGet { current -> maxOf(current, depth) }
    }

    fun recordEdge() {
        _totalEdges.incrementAndGet()
    }

    fun recordTruncation() {
        _truncatedNodes.incrementAndGet()
    }

    fun timeoutReached(): Boolean {
        if (timeoutHit.get()) {
            return true
        }
        if (System.nanoTime() - startedAtNanos >= timeoutNanos) {
            timeoutHit.set(true)
        }
        return timeoutHit.get()
    }

    fun toStats(): CallHierarchyStats = CallHierarchyStats(
        totalNodes = totalNodes,
        totalEdges = totalEdges,
        truncatedNodes = truncatedNodes,
        maxDepthReached = maxDepthReached,
        timeoutReached = timeoutHit.get(),
        maxTotalCallsReached = maxTotalCallsHit.get(),
        maxChildrenPerNodeReached = maxChildrenHit.get(),
        filesVisited = visitedFiles.size,
    )
}
