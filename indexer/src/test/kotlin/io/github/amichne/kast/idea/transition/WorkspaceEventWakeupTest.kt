package io.github.amichne.kast.idea.transition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceEventWakeupTest {
    @Test
    fun `source event promptly wakes the waiting transition worker`() {
        val wakeup = WorkspaceEventWakeup()
        val waiting = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val observedWakeup = AtomicReference<WorkspaceWakeup>()
        val worker = thread(start = true, isDaemon = true) {
            waiting.countDown()
            observedWakeup.set(wakeup.awaitWakeup(TimeUnit.MINUTES.toMillis(5)))
            completed.countDown()
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        wakeup.signal(WorkspaceSignal.Source)

        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(WorkspaceWakeup.Signal, observedWakeup.get())
        assertEquals(setOf(WorkspaceSignal.Source), wakeup.drainSignals())
        worker.join(1_000)
    }

    @Test
    fun `audit timeout wakes the worker when no event arrives`() {
        var now = 0L
        val wakeup = WorkspaceEventWakeup(
            nanoTime = { now },
            awaitCondition = { _, remainingNanos ->
                now += remainingNanos
                0L
            },
        )

        assertEquals(WorkspaceWakeup.RecoveryAudit, wakeup.awaitWakeup(TimeUnit.MINUTES.toMillis(5)))
        assertEquals(emptySet<WorkspaceSignal>(), wakeup.drainSignals())
    }

    @Test
    fun `predicate false wake neither signals nor restarts the recovery audit deadline`() {
        val auditNanos = TimeUnit.MINUTES.toNanos(5)
        val firstWakeNanos = TimeUnit.MINUTES.toNanos(2)
        var now = 0L
        val requestedWaits = mutableListOf<Long>()
        val wakeup = WorkspaceEventWakeup(
            nanoTime = { now },
            awaitCondition = { _, remainingNanos ->
                requestedWaits += remainingNanos
                now += if (requestedWaits.size == 1) firstWakeNanos else remainingNanos
                remainingNanos - firstWakeNanos
            },
        )

        assertEquals(WorkspaceWakeup.RecoveryAudit, wakeup.awaitWakeup(TimeUnit.MINUTES.toMillis(5)))
        assertEquals(listOf(auditNanos, auditNanos - firstWakeNanos), requestedWaits)
        assertEquals(emptySet<WorkspaceSignal>(), wakeup.drainSignals())
    }

    @Test
    fun `interrupt stops the wait and preserves the interrupt status`() {
        val wakeup = WorkspaceEventWakeup(
            awaitCondition = { _, _ -> throw InterruptedException("test interrupt") },
        )

        try {
            assertEquals(WorkspaceWakeup.Interrupted, wakeup.awaitWakeup(TimeUnit.MINUTES.toMillis(5)))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
