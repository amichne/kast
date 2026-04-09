package io.github.amichne.kast.standalone

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Abstraction over read-write lock semantics for the analysis session.
 *
 * Production code uses [ReentrantSessionLock]; tests can inject
 * [InstrumentedSessionLock] to assert on lock contention properties.
 */
internal interface SessionLock {
    fun <T> read(action: () -> T): T
    fun <T> write(action: () -> T): T
}

internal class ReentrantSessionLock : SessionLock {
    private val lock = ReentrantReadWriteLock()
    override fun <T> read(action: () -> T): T = lock.read(action)
    override fun <T> write(action: () -> T): T = lock.write(action)
}

/**
 * Test double that records lock acquisition events for concurrency assertions.
 *
 * Thread-safe: events are stored in a [CopyOnWriteArrayList].
 */
internal class InstrumentedSessionLock(
    private val clock: Clock = Clock.SYSTEM,
) : SessionLock {
    data class LockEvent(
        val type: LockType,
        val threadName: String,
        val acquiredAtNanos: Long,
        val releasedAtNanos: Long,
    )

    enum class LockType { READ, WRITE }

    private val delegate = ReentrantSessionLock()
    private val _events = CopyOnWriteArrayList<LockEvent>()
    val events: List<LockEvent> get() = _events.toList()

    override fun <T> read(action: () -> T): T {
        val start = clock.nanoTime()
        return delegate.read(action).also {
            _events += LockEvent(LockType.READ, Thread.currentThread().name, start, clock.nanoTime())
        }
    }

    override fun <T> write(action: () -> T): T {
        val start = clock.nanoTime()
        return delegate.write(action).also {
            _events += LockEvent(LockType.WRITE, Thread.currentThread().name, start, clock.nanoTime())
        }
    }

    fun maxWriteHoldNanos(): Long = _events
        .filter { it.type == LockType.WRITE }
        .maxOfOrNull { it.releasedAtNanos - it.acquiredAtNanos } ?: 0L

    fun writeEventsOverlappingReads(): List<Pair<LockEvent, LockEvent>> {
        val writes = _events.filter { it.type == LockType.WRITE }
        val reads = _events.filter { it.type == LockType.READ }
        return writes.flatMap { w ->
            reads.filter { r ->
                r.acquiredAtNanos < w.releasedAtNanos && r.releasedAtNanos > w.acquiredAtNanos
            }.map { r -> w to r }
        }
    }

    fun clearEvents() {
        _events.clear()
    }
}
