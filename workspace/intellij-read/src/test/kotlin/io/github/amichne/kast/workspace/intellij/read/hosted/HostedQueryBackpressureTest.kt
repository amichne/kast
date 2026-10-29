@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryBackpressureTest {
    @Test
    fun `queued deadline expires before computation and cancellation removes only its waiter`() = runTest {
        val limits = (ReadLimits.resolve(mapOf("KAST_READ_HOST_READERS" to "1")) as Refinement.Refined).value
        val executor = HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000 })
        val release = CompletableDeferred<Unit>()
        val active = async {
            executor.execute(executor.endpoint, limits) {
                release.await()
                1
            }
        }
        runCurrent()
        val cancelled = async {
            executor.execute(executor.endpoint, limits) { error("Cancelled waiter entered computation") }
        }
        val expired = async {
            executor.execute(
                executor.endpoint,
                limits,
                deadline = HostedReadDeadline(shortHostLimits(), { testScheduler.currentTime * 1_000_000 }),
            ) {
                error("Expired waiter entered computation")
            }
        }
        runCurrent()
        assertFalse(cancelled.isCompleted)
        assertFalse(expired.isCompleted)
        cancelled.cancel()
        cancelled.join()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), expired.await())
        release.complete(Unit)
        assertEquals(HostedExecution.Completed(1), active.await())
        assertEquals(HostedExecution.Completed(2), executor.execute(executor.endpoint, limits) { 2 })
        executor.retire()
        executor.drain()
    }

    @Test
    fun `retirement rejects queued work without invoking it`() = runTest {
        val limits = (ReadLimits.resolve(mapOf("KAST_READ_HOST_READERS" to "1")) as Refinement.Refined).value
        val executor = HostedQueryExecutor(backgroundScope)
        val active = async { executor.execute(executor.endpoint, limits) { awaitCancellation() } }
        runCurrent()
        val waiting = async {
            executor.execute(executor.endpoint, limits) { error("Retired waiter entered computation") }
        }
        runCurrent()
        assertFalse(waiting.isCompleted)
        executor.retire()
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.RETIRED), waiting.await())
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.RETIRED), active.await())
        executor.drain()
    }

    @Test
    fun `full queue rejects excess work and cancellation restores its capacity`() = runTest {
        val limits =
            (ReadLimits.resolve(
                    mapOf(
                        "KAST_READ_HOST_READERS" to "1",
                        "KAST_READ_HOST_CONNECTIONS" to "1",
                    )
                ) as Refinement.Refined)
                .value
        val executor = HostedQueryExecutor(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val active = async {
            executor.execute(executor.endpoint, limits) {
                release.await()
                1
            }
        }
        runCurrent()
        val waiting = async { executor.execute(executor.endpoint, limits) { error("Cancelled waiter entered") } }
        runCurrent()
        assertEquals(
            HostedExecution.Rejected(HostedQueryFailure.BUSY),
            executor.execute(executor.endpoint, limits) { error("Excess waiter entered") },
        )
        waiting.cancel()
        waiting.join()
        val replacement = async { executor.execute(executor.endpoint, limits) { 2 } }
        runCurrent()
        assertFalse(replacement.isCompleted)
        release.complete(Unit)
        assertEquals(HostedExecution.Completed(1), active.await())
        assertEquals(HostedExecution.Completed(2), replacement.await())
        executor.retire()
        executor.drain()
    }

    @Test
    fun `four and eight reader bursts respect capacity and drain every queued read`() = runTest {
        for (capacity in listOf(4, 8)) {
            val limits =
                (ReadLimits.resolve(mapOf("KAST_READ_HOST_READERS" to capacity.toString())) as Refinement.Refined).value
            val executor = HostedQueryExecutor(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val entered = mutableListOf<Int>()
            val work =
                List(capacity * 2) { index ->
                    async {
                        executor.execute(executor.endpoint, limits) {
                            entered += index
                            release.await()
                            index
                        }
                    }
                }
            runCurrent()
            assertEquals((0 until capacity).toList(), entered)
            assertTrue(work.none { it.isCompleted })
            release.complete(Unit)
            work.forEachIndexed { index, read -> assertEquals(HostedExecution.Completed(index), read.await()) }
            assertEquals((0 until capacity * 2).toList(), entered)
            executor.retire()
            executor.drain()
        }
    }
}
