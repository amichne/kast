package io.github.amichne.kast.idea.transition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val worker = thread(start = true, isDaemon = true) {
            waiting.countDown()
            if (wakeup.awaitSignalOrAudit(TimeUnit.MINUTES.toMillis(5))) completed.countDown()
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        wakeup.signal(WorkspaceSignal.Source)

        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(setOf(WorkspaceSignal.Source), wakeup.drainSignals())
        worker.join(1_000)
    }

    @Test
    fun `audit timeout wakes the worker when no event arrives`() {
        val wakeup = WorkspaceEventWakeup()

        assertTrue(wakeup.awaitSignalOrAudit(1))
        assertEquals(emptySet<WorkspaceSignal>(), wakeup.drainSignals())
    }
}
