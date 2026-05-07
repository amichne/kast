package io.github.amichne.kast.standalone

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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

    /**
     * Attempts to acquire the write lock within [timeoutMillis] milliseconds.
     *
     * Returns the result of [action] when the lock was acquired, or `null` when the
     * acquisition timed out.  The lock is always released if it was acquired, even if
     * [action] throws.
     *
     * **Caller contract**: [T] must be non-nullable.  A `null` return always means
     * "timeout"; a non-null return always means the action completed under the lock.
     */
    fun <T> tryWrite(timeoutMillis: Long, action: () -> T): T?
}

internal class ReentrantSessionLock : SessionLock {
    /**
     * Fair mode keeps background Phase 2 writes from starving behind continuous foreground reads.
     */
    private val lock = ReentrantReadWriteLock(/* fair = */ true)
    override fun <T> read(action: () -> T): T = lock.read(action)
    override fun <T> write(action: () -> T): T = lock.write(action)

    /**
     * Attempts to acquire the write lock within [timeoutMillis] milliseconds.
     * Returns `null` on timeout; propagates any exception thrown by [action].
     */
    override fun <T> tryWrite(timeoutMillis: Long, action: () -> T): T? {
        if (!lock.writeLock().tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return null
        return try {
            action()
        } finally {
            lock.writeLock().unlock()
        }
    }
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

    /**
     * Delegates to [ReentrantSessionLock.tryWrite].  Records a [LockType.WRITE] event only
     * when the lock was successfully acquired (i.e., the return value is non-null).
     */
    override fun <T> tryWrite(timeoutMillis: Long, action: () -> T): T? {
        val start = clock.nanoTime()
        return delegate.tryWrite(timeoutMillis, action)?.also {
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
