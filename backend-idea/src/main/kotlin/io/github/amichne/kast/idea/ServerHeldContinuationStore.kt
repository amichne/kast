package io.github.amichne.kast.idea

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal class ServerHeldContinuationStore<Key, Value>(
    private val maxEntries: Int,
    timeToLive: Duration = DEFAULT_TIME_TO_LIVE,
    private val clock: ContinuationClock = ContinuationClock.System,
    private val onDiscard: (Value) -> Unit = {},
) {
    private val timeToLiveNanos = timeToLive.inWholeNanoseconds
    private val entries = LinkedHashMap<Key, Entry<Value>>()

    init {
        require(maxEntries > 0) { "Server-held continuation capacity must be positive" }
        require(timeToLive.isPositive()) { "Server-held continuation time to live must be positive" }
    }

    @Synchronized
    fun claim(key: Key): ContinuationClaim<Value> {
        val entry = entries.remove(key) ?: return ContinuationClaim.Absent
        return if (isExpired(entry, clock.nowNanos())) {
            onDiscard(entry.value)
            ContinuationClaim.Expired
        } else {
            ContinuationClaim.Claimed(entry.value)
        }
    }

    @Synchronized
    fun put(key: Key, value: Value) {
        val nowNanos = clock.nowNanos()
        purgeExpiredAt(nowNanos)
        entries.remove(key)?.let { replaced -> onDiscard(replaced.value) }
        entries[key] = Entry(value, nowNanos)
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            val discarded = iterator.next().value
            iterator.remove()
            onDiscard(discarded.value)
        }
    }

    @Synchronized
    fun purgeExpired(): Int = purgeExpiredAt(clock.nowNanos())

    @Synchronized
    fun closeAll() {
        val retained = entries.values.toList()
        entries.clear()
        var firstFailure: Throwable? = null
        retained.forEach { entry ->
            try {
                onDiscard(entry.value)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { failure -> throw failure }
    }

    private fun purgeExpiredAt(nowNanos: Long): Int {
        var discardedCount = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (isExpired(entry, nowNanos)) {
                iterator.remove()
                onDiscard(entry.value)
                discardedCount += 1
            }
        }
        return discardedCount
    }

    private fun isExpired(entry: Entry<Value>, nowNanos: Long): Boolean =
        nowNanos - entry.createdAtNanos >= timeToLiveNanos

    private data class Entry<Value>(
        val value: Value,
        val createdAtNanos: Long,
    )

    private companion object {
        val DEFAULT_TIME_TO_LIVE: Duration = 2.minutes
    }
}
