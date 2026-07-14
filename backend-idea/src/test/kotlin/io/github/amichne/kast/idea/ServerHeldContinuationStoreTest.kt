package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
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

    @Test
    fun `close all is terminal and disposes a late insertion exactly once`() {
        val discarded = mutableListOf<RetainedValue>()
        val store = ServerHeldContinuationStore<String, RetainedValue>(
            maxEntries = 1,
            timeToLive = 5.seconds,
            onDiscard = discarded::add,
        )
        val late = RetainedValue("late")

        store.closeAll()
        store.put("late", late)

        assertEquals(ContinuationClaim.Absent, store.claim("late"))
        assertEquals(listOf(late), discarded)

        store.closeAll()
        assertEquals(listOf(late), discarded)
    }

    @Test
    fun `claimed continuation racing shutdown cannot be reissued into the store`() {
        val discarded = mutableListOf<RetainedValue>()
        val store = ServerHeldContinuationStore<String, RetainedValue>(
            maxEntries = 1,
            timeToLive = 5.seconds,
            onDiscard = discarded::add,
        )
        val value = RetainedValue("claimed-before-shutdown")
        store.put("first", value)
        val claimed = (store.claim("first") as ContinuationClaim.Claimed).value
        val shutdownCompleted = CountDownLatch(1)
        val reissueCompleted = CountDownLatch(1)
        val reissuer = thread(name = "continuation-reissuer") {
            assertTrue(shutdownCompleted.await(5, TimeUnit.SECONDS))
            store.put("second", claimed)
            reissueCompleted.countDown()
        }

        store.closeAll()
        shutdownCompleted.countDown()
        assertTrue(reissueCompleted.await(5, TimeUnit.SECONDS))
        reissuer.join(5_000)

        assertEquals(ContinuationClaim.Absent, store.claim("second"))
        assertEquals(listOf(value), discarded)
    }

    @Test
    fun `abandoned continuation expires passively without a later store operation`() {
        val discarded = AtomicInteger()
        val expired = CountDownLatch(1)
        val store = ServerHeldContinuationStore<String, RetainedValue>(
            maxEntries = 1,
            timeToLive = 50.milliseconds,
            onDiscard = {
                discarded.incrementAndGet()
                expired.countDown()
            },
        )
        store.put("abandoned", RetainedValue("abandoned"))

        assertTrue(expired.await(5, TimeUnit.SECONDS), "continuation was not passively expired")
        assertEquals(1, discarded.get())

        store.closeAll()
        assertEquals(1, discarded.get())
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
