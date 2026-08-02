package io.github.amichne.kast.shared.hierarchy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TraversalBudgetTest {

    @Test
    fun `recordNode increments totalNodes atomically under contention`() {
        val budget = TraversalBudget(
            maxTotalCalls = Int.MAX_VALUE,
            maxChildrenPerNode = Int.MAX_VALUE,
            timeoutMillis = 60_000,
        )
        val threads = 8
        val incrementsPerThread = 1_000
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                executor.submit {
                    latch.await()
                    repeat(incrementsPerThread) { i ->
                        budget.recordNode(depth = i)
                    }
                }
            }
            latch.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        assertEquals(threads * incrementsPerThread, budget.totalNodes)
        assertEquals(incrementsPerThread - 1, budget.maxDepthReached)
    }

    @Test
    fun `recordEdge increments totalEdges atomically under contention`() {
        val budget = TraversalBudget(
            maxTotalCalls = Int.MAX_VALUE,
            maxChildrenPerNode = Int.MAX_VALUE,
            timeoutMillis = 60_000,
        )
        val threads = 8
        val incrementsPerThread = 1_000
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                executor.submit {
                    latch.await()
                    repeat(incrementsPerThread) {
                        budget.recordEdge()
                    }
                }
            }
            latch.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        assertEquals(threads * incrementsPerThread, budget.totalEdges)
    }

    @Test
    fun `recordTruncation increments truncatedNodes atomically under contention`() {
        val budget = TraversalBudget(
            maxTotalCalls = Int.MAX_VALUE,
            maxChildrenPerNode = Int.MAX_VALUE,
            timeoutMillis = 60_000,
        )
        val threads = 8
        val incrementsPerThread = 500
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                executor.submit {
                    latch.await()
                    repeat(incrementsPerThread) {
                        budget.recordTruncation()
                    }
                }
            }
            latch.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        assertEquals(threads * incrementsPerThread, budget.truncatedNodes)
    }

    @Test
    fun `timeoutReached returns true after timeout expires`() {
        val budget = TraversalBudget(
            maxTotalCalls = 100,
            maxChildrenPerNode = 10,
            timeoutMillis = 1,
        )
        Thread.sleep(5)
        assertTrue(budget.timeoutReached())
        assertTrue(budget.timeoutHit.get())
    }

    @Test
    fun `timeoutReached returns false before timeout`() {
        val budget = TraversalBudget(
            maxTotalCalls = 100,
            maxChildrenPerNode = 10,
            timeoutMillis = 60_000,
        )
        assertFalse(budget.timeoutReached())
        assertFalse(budget.timeoutHit.get())
    }

    @Test
    fun `toStats reflects accumulated counters`() {
        val budget = TraversalBudget(
            maxTotalCalls = 100,
            maxChildrenPerNode = 10,
            timeoutMillis = 60_000,
        )
        budget.recordNode(depth = 0)
        budget.recordNode(depth = 1)
        budget.recordNode(depth = 2)
        budget.recordEdge()
        budget.recordEdge()
        budget.recordTruncation()
        budget.visitFile("/a.kt")
        budget.visitFile("/b.kt")
        budget.visitFile("/a.kt") // duplicate

        val stats = budget.toStats()
        assertEquals(3, stats.totalNodes)
        assertEquals(2, stats.totalEdges)
        assertEquals(1, stats.truncatedNodes)
        assertEquals(2, stats.maxDepthReached)
        assertEquals(2, stats.filesVisited)
        assertFalse(stats.timeoutReached)
        assertFalse(stats.maxTotalCallsReached)
        assertFalse(stats.maxChildrenPerNodeReached)
    }

    @Test
    fun `atomic boolean flags are reflected in toStats`() {
        val budget = TraversalBudget(
            maxTotalCalls = 100,
            maxChildrenPerNode = 10,
            timeoutMillis = 60_000,
        )
        budget.maxTotalCallsHit.set(true)
        budget.maxChildrenHit.set(true)

        val stats = budget.toStats()
        assertTrue(stats.maxTotalCallsReached)
        assertTrue(stats.maxChildrenPerNodeReached)
    }
}
