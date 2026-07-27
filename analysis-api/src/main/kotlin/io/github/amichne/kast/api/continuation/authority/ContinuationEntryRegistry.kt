package io.github.amichne.kast.api.continuation

import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ContinuationEntryRegistry<
    Token : Any,
    Query : Any,
    State : ContinuationOwnedState,
>(
    private val capacity: ContinuationCapacity,
    private val timeToLive: ContinuationTtl,
    private val clock: ContinuationClock,
) {
    private val entries = LinkedHashMap<Token, ContinuationEntry<Query, State>>()
    private var expiryTask: ScheduledFuture<*>? = null

    fun nowNanos(): Long = clock.nowNanos()

    fun removeLocked(token: Token): ContinuationEntry<Query, State>? = entries.remove(token)

    fun putLocked(token: Token, entry: ContinuationEntry<Query, State>) {
        entries[token] = entry
    }

    fun containsLocked(token: Token): Boolean = token in entries

    fun isExpired(entry: ContinuationEntry<Query, State>): Boolean =
        elapsedNanos(entry.createdAtNanos) >= timeToLive.nanoseconds

    fun drainStatesLocked(): List<State> =
        entries.values.map(ContinuationEntry<Query, State>::state).also { entries.clear() }

    fun removeExpiredLocked(nowNanos: Long): List<State> = buildList {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowNanos - entry.createdAtNanos >= timeToLive.nanoseconds) {
                iterator.remove()
                add(entry.state)
            }
        }
    }

    fun removeOverCapacityLocked(nowNanos: Long): List<State> = buildList {
        while (entries.size > capacity.value) {
            add(removeOldestLocked(nowNanos))
        }
    }

    fun removeForPublicationCapacityLocked(nowNanos: Long): List<State> = buildList {
        while (entries.size >= capacity.value) {
            add(removeOldestLocked(nowNanos))
        }
    }

    fun <Projection : ContinuationProjection> scheduleNextExpiryLocked(
        nowNanos: Long,
        ownership: ContinuationStoreOwnership<Token, Query, State, Projection>,
    ) {
        expiryTask?.cancel(false)
        expiryTask = null
        if (ownership.isClosingLocked() || entries.isEmpty()) return
        val elapsedNanos = entries.values.maxOf { entry ->
            nowNanos - entry.createdAtNanos
        }
        val delayNanos = (timeToLive.nanoseconds - elapsedNanos).coerceAtLeast(0L)
        expiryTask = EXPIRY_EXECUTOR.schedule(
            PassiveExpiryTask(ownership),
            delayNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    fun cancelExpiryLocked() {
        expiryTask?.cancel(false)
        expiryTask = null
    }

    fun expiryTaskCompletedLocked() {
        expiryTask = null
    }

    private fun removeOldestLocked(nowNanos: Long): State {
        val oldest = entries.entries.maxBy { (_, entry) ->
            nowNanos - entry.createdAtNanos
        }
        entries.remove(oldest.key)
        return oldest.value.state
    }

    private fun elapsedNanos(createdAtNanos: Long): Long =
        clock.nowNanos() - createdAtNanos
}

private class PassiveExpiryTask<
    Token : Any,
    Query : Any,
    State : ContinuationOwnedState,
    Projection : ContinuationProjection,
>(
    ownership: ContinuationStoreOwnership<Token, Query, State, Projection>,
) : Runnable {
    private val ownership = WeakReference(ownership)

    override fun run() {
        runCatching { ownership.get()?.expirePassively() }
    }
}

private val EXPIRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "kast-continuation-expiry").apply { isDaemon = true }
}.apply {
    removeOnCancelPolicy = true
}
