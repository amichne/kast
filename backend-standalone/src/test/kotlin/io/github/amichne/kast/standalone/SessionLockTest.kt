package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.thread

class SessionLockTest {

    @Test
    fun `InstrumentedSessionLock records read events`() {
        val clock = TestClock()
        val lock = InstrumentedSessionLock(clock)

        clock.advanceNanos(100)
        lock.read { clock.advanceNanos(50) }

        val events = lock.events
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(InstrumentedSessionLock.LockType.READ, event.type)
        assertEquals(100L, event.acquiredAtNanos)
        assertEquals(150L, event.releasedAtNanos)
    }

    @Test
    fun `InstrumentedSessionLock records write events`() {
        val clock = TestClock()
        val lock = InstrumentedSessionLock(clock)

        clock.advanceNanos(200)
        lock.write { clock.advanceNanos(300) }

        val events = lock.events
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(InstrumentedSessionLock.LockType.WRITE, event.type)
        assertEquals(200L, event.acquiredAtNanos)
        assertEquals(500L, event.releasedAtNanos)
    }

    @Test
    fun `maxWriteHoldNanos returns correct duration`() {
        val clock = TestClock()
        val lock = InstrumentedSessionLock(clock)

        lock.write { clock.advanceNanos(100) }
        lock.write { clock.advanceNanos(500) }
        lock.write { clock.advanceNanos(200) }

        assertEquals(500L, lock.maxWriteHoldNanos())
    }

    @Test
    fun `maxWriteHoldNanos returns zero when no write events`() {
        val lock = InstrumentedSessionLock()
        assertEquals(0L, lock.maxWriteHoldNanos())
    }

    @Test
    fun `queued write event starts when lock is acquired not when requested`() {
        val lock = InstrumentedSessionLock()
        val readAcquired = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)

        val reader = thread {
            lock.read {
                readAcquired.countDown()
                releaseRead.await()
            }
        }
        assertTrue(readAcquired.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val writer = thread {
            lock.write {}
            writeFinished.countDown()
        }
        Thread.sleep(100)
        releaseRead.countDown()

        assertTrue(writeFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        reader.join(5_000)
        writer.join(5_000)

        assertTrue(lock.writeEventsOverlappingReads().isEmpty())
    }

    @Test
    fun `concurrent reads do not block each other`() {
        val lock = ReentrantSessionLock()
        val barrier = CyclicBarrier(2)
        val completedReads = java.util.concurrent.atomic.AtomicInteger(0)

        val t1 = thread {
            lock.read {
                barrier.await() // both threads hold read lock simultaneously
                completedReads.incrementAndGet()
            }
        }
        val t2 = thread {
            lock.read {
                barrier.await() // both threads hold read lock simultaneously
                completedReads.incrementAndGet()
            }
        }

        t1.join(5_000)
        t2.join(5_000)
        assertEquals(2, completedReads.get(), "Both reads should complete concurrently")
    }

    @Test
    fun `write blocks concurrent reads`() {
        val lock = ReentrantSessionLock()
        val writeLatch = CountDownLatch(1)
        val writeHeld = CountDownLatch(1)
        val readCompleted = AtomicBoolean(false)

        val writer = thread {
            lock.write {
                writeHeld.countDown()
                writeLatch.await() // hold write lock until released
            }
        }

        writeHeld.await() // wait for write to be acquired

        val reader = thread {
            lock.read {
                readCompleted.set(true)
            }
        }

        Thread.sleep(100) // give reader time to attempt acquisition
        assertTrue(!readCompleted.get(), "Read should be blocked while write is held")

        writeLatch.countDown() // release write lock
        reader.join(5_000)
        assertTrue(readCompleted.get(), "Read should complete after write releases")

        writer.join(5_000)
    }

    @Test
    fun `ReentrantSessionLock uses fair read write lock`() {
        val delegateField = ReentrantSessionLock::class.java.getDeclaredField("lock").apply {
            isAccessible = true
        }
        val delegate = delegateField.get(ReentrantSessionLock()) as ReentrantReadWriteLock

        assertTrue(delegate.isFair, "Session lock must be fair so queued writers are not starved by new readers")
    }

    @Test
    fun `clearEvents removes all recorded events`() {
        val lock = InstrumentedSessionLock()
        lock.read { }
        lock.write { }
        assertEquals(2, lock.events.size)

        lock.clearEvents()
        assertEquals(0, lock.events.size)
    }

    @Test
    fun `ReentrantSessionLock read returns action result`() {
        val lock = ReentrantSessionLock()
        val result = lock.read { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `ReentrantSessionLock write returns action result`() {
        val lock = ReentrantSessionLock()
        val result = lock.write { "hello" }
        assertEquals("hello", result)
    }
}
