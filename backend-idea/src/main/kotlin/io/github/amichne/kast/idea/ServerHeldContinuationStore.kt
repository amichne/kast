package io.github.amichne.kast.idea

import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ServerHeldContinuationStore<Key, Value>(
    private val maxEntries: Int,
    timeToLive: Duration = DEFAULT_TIME_TO_LIVE,
    private val clock: ContinuationClock = ContinuationClock.System,
    private val onDiscard: (Value) -> Unit = {},
) {
    private val timeToLiveNanos = timeToLive.toNanos()
    private val entries = LinkedHashMap<Key, Entry<Value>>()
    private var expiryTask: ScheduledFuture<*>? = null
    private var closed = false

    init {
        require(maxEntries > 0) { "Server-held continuation capacity must be positive" }
        require(!timeToLive.isZero && !timeToLive.isNegative) {
            "Server-held continuation time to live must be positive"
        }
    }

    @Synchronized
    fun claim(key: Key): ContinuationClaim<Value> {
        if (closed) return ContinuationClaim.Absent
        val entry = entries.remove(key) ?: return ContinuationClaim.Absent
        val nowNanos = clock.nowNanos()
        scheduleNextExpiryAt(nowNanos)
        return if (isExpired(entry, nowNanos)) {
            discardAll(listOf(entry.value))
            ContinuationClaim.Expired
        } else {
            ContinuationClaim.Claimed(entry.value)
        }
    }

    @Synchronized
    fun put(key: Key, value: Value) {
        if (closed) {
            discardAll(listOf(value))
            return
        }
        val nowNanos = clock.nowNanos()
        val discarded = removeExpiredAt(nowNanos).toMutableList()
        entries.remove(key)?.let { replaced -> discarded += replaced.value }
        entries[key] = Entry(value, nowNanos)
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            val evicted = iterator.next().value
            iterator.remove()
            discarded += evicted.value
        }
        scheduleNextExpiryAt(nowNanos)
        discardAll(discarded)
    }

    @Synchronized
    fun purgeExpired(): Int {
        if (closed) return 0
        val nowNanos = clock.nowNanos()
        val discarded = removeExpiredAt(nowNanos)
        scheduleNextExpiryAt(nowNanos)
        discardAll(discarded)
        return discarded.size
    }

    @Synchronized
    fun closeAll() {
        if (closed) return
        closed = true
        expiryTask?.cancel(false)
        expiryTask = null
        val retained = entries.values.toList()
        entries.clear()
        discardAll(retained.map { entry -> entry.value })
    }

    @Synchronized
    private fun expirePassively() {
        expiryTask = null
        if (closed) return
        val nowNanos = clock.nowNanos()
        val discarded = removeExpiredAt(nowNanos)
        scheduleNextExpiryAt(nowNanos)
        discardAll(discarded)
    }

    private fun removeExpiredAt(nowNanos: Long): List<Value> = buildList {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (isExpired(entry, nowNanos)) {
                iterator.remove()
                add(entry.value)
            }
        }
    }

    private fun scheduleNextExpiryAt(nowNanos: Long) {
        expiryTask?.cancel(false)
        expiryTask = null
        if (closed || entries.isEmpty()) return
        val earliestCreatedAt = entries.values.minOf { entry -> entry.createdAtNanos }
        val elapsedNanos = nowNanos - earliestCreatedAt
        val delayNanos = (timeToLiveNanos - elapsedNanos).coerceAtLeast(0L)
        expiryTask = EXPIRY_EXECUTOR.schedule(
            PassiveExpiryTask(this),
            delayNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    private fun discardAll(values: List<Value>) {
        var firstFailure: Throwable? = null
        values.forEach { value ->
            try {
                onDiscard(value)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { failure -> throw failure }
    }

    private fun isExpired(entry: Entry<Value>, nowNanos: Long): Boolean =
        nowNanos - entry.createdAtNanos >= timeToLiveNanos

    private data class Entry<Value>(
        val value: Value,
        val createdAtNanos: Long,
    )

    private class PassiveExpiryTask<Key, Value>(
        store: ServerHeldContinuationStore<Key, Value>,
    ) : Runnable {
        private val store = WeakReference(store)

        override fun run() {
            runCatching { store.get()?.expirePassively() }
        }
    }

    private companion object {
        val DEFAULT_TIME_TO_LIVE: Duration = Duration.ofMinutes(2)
        val EXPIRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "kast-continuation-expiry").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }
    }
}
