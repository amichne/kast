package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ServerHeldContinuationStoreTest {
    @Test
    fun `claim expires and disposes retained value exactly once`() {
        val clock = MutableContinuationClock()
        val discarded = mutableListOf<RetainedValue>()
        val store = ServerHeldContinuationStore<String, RetainedValue>(
            maxEntries = 2,
            timeToLive = 5.seconds,
            clock = clock,
            onDiscard = discarded::add,
        )
        val value = RetainedValue("expired")
        store.put("token", value)

        clock.advance(6.seconds.inWholeNanoseconds)

        assertEquals(ContinuationClaim.Expired, store.claim("token"))
        assertEquals(listOf(value), discarded)
        assertEquals(ContinuationClaim.Absent, store.claim("token"))
        assertEquals(listOf(value), discarded)
    }

    @Test
    fun `capacity replacement collision and close all each dispose once`() {
        val discarded = mutableListOf<RetainedValue>()
        val store = ServerHeldContinuationStore<String, RetainedValue>(
            maxEntries = 2,
            timeToLive = 5.seconds,
            onDiscard = discarded::add,
        )
        val replaced = RetainedValue("replaced")
        val oldest = RetainedValue("oldest")
        val replacement = RetainedValue("replacement")
        val newest = RetainedValue("newest")
        store.put("collision", replaced)
        store.put("oldest", oldest)

        store.put("collision", replacement)
        store.put("newest", newest)
        store.closeAll()
        store.closeAll()

        assertEquals(listOf(replaced, oldest, replacement, newest), discarded)
    }

    @Test
    fun `claimed value transfers disposal ownership to caller`() {
        val discarded = mutableListOf<RetainedValue>()
        val store = ServerHeldContinuationStore<String, RetainedValue>(
            maxEntries = 1,
            timeToLive = 5.seconds,
            onDiscard = discarded::add,
        )
        val value = RetainedValue("claimed")
        store.put("token", value)

        val claim = store.claim("token") as ContinuationClaim.Claimed

        assertSame(value, claim.value)
        assertEquals(emptyList<RetainedValue>(), discarded)
        assertEquals(ContinuationClaim.Absent, store.claim("token"))
    }

    private data class RetainedValue(val name: String)

    private class MutableContinuationClock : ContinuationClock {
        private var nowNanos: Long = 0

        override fun nowNanos(): Long = nowNanos

        fun advance(nanos: Long) {
            nowNanos = Math.addExact(nowNanos, nanos)
        }
    }
}
