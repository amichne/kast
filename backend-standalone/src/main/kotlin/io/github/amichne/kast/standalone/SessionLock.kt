package io.github.amichne.kast.standalone

import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
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
 * Production lock wrapper that emits telemetry spans for lock wait and hold
 * durations. Only emits spans when the wait time exceeds [waitThresholdNanos]
 * to avoid flooding the trace output for uncontended acquisitions.
 */
internal class TelemetrySessionLock(
    private val telemetry: StandaloneTelemetry,
    private val waitThresholdNanos: Long = 1_000_000L, // 1 ms
) : SessionLock {
    private val delegate = ReentrantSessionLock()

    override fun <T> read(action: () -> T): T {
        val entryNanos = System.nanoTime()
        return delegate.read {
            val acquiredNanos = System.nanoTime()
            val result = action()
            val releaseNanos = System.nanoTime()
            emitSpan(
                name = "kast.lock.read",
                waitNanos = acquiredNanos - entryNanos,
                holdNanos = releaseNanos - acquiredNanos,
            )
            result
        }
    }

    override fun <T> write(action: () -> T): T {
        val entryNanos = System.nanoTime()
        return delegate.write {
            val acquiredNanos = System.nanoTime()
            val result = action()
            val releaseNanos = System.nanoTime()
            emitSpan(
                name = "kast.lock.write",
                waitNanos = acquiredNanos - entryNanos,
                holdNanos = releaseNanos - acquiredNanos,
            )
            result
        }
    }

    override fun <T> tryWrite(timeoutMillis: Long, action: () -> T): T? {
        val entryNanos = System.nanoTime()
        return delegate.tryWrite(timeoutMillis) {
            val acquiredNanos = System.nanoTime()
            val result = action()
            val releaseNanos = System.nanoTime()
            emitSpan(
                name = "kast.lock.write",
                waitNanos = acquiredNanos - entryNanos,
                holdNanos = releaseNanos - acquiredNanos,
                acquired = true,
            )
            result
        }.also { outcome ->
            if (outcome == null) {
                val waitNanos = System.nanoTime() - entryNanos
                emitSpan(
                    name = "kast.lock.write",
                    waitNanos = waitNanos,
                    holdNanos = 0L,
                    acquired = false,
                )
            }
        }
    }

    private fun emitSpan(
        name: String,
        waitNanos: Long,
        holdNanos: Long,
        acquired: Boolean = true,
    ) {
        if (waitNanos < waitThresholdNanos) return
        telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LOCK,
            name = name,
            attributes = mapOf(
                "kast.lock.waitNanos" to waitNanos,
                "kast.lock.holdNanos" to holdNanos,
                "kast.lock.caller" to Thread.currentThread().name,
                "kast.lock.acquired" to acquired,
            ),
        ) { /* attributes already attached */ }
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

    private val lock = ReentrantReadWriteLock(/* fair = */ true)
    private val _events = CopyOnWriteArrayList<LockEvent>()
    val events: List<LockEvent> get() = _events.toList()

    override fun <T> read(action: () -> T): T {
        lock.readLock().lock()
        val acquiredAtNanos = clock.nanoTime()
        return try {
            action()
        } finally {
            val releasedAtNanos = clock.nanoTime()
            lock.readLock().unlock()
            _events += LockEvent(LockType.READ, Thread.currentThread().name, acquiredAtNanos, releasedAtNanos)
        }
    }

    override fun <T> write(action: () -> T): T {
        lock.writeLock().lock()
        val acquiredAtNanos = clock.nanoTime()
        return try {
            action()
        } finally {
            val releasedAtNanos = clock.nanoTime()
            lock.writeLock().unlock()
            _events += LockEvent(LockType.WRITE, Thread.currentThread().name, acquiredAtNanos, releasedAtNanos)
        }
    }

    /**
     * Delegates to [ReentrantSessionLock.tryWrite].  Records a [LockType.WRITE] event only
     * when the lock was successfully acquired (i.e., the return value is non-null).
     */
    override fun <T> tryWrite(timeoutMillis: Long, action: () -> T): T? {
        if (!lock.writeLock().tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return null
        val acquiredAtNanos = clock.nanoTime()
        return try {
            action()
        } finally {
            val releasedAtNanos = clock.nanoTime()
            lock.writeLock().unlock()
            _events += LockEvent(LockType.WRITE, Thread.currentThread().name, acquiredAtNanos, releasedAtNanos)
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
