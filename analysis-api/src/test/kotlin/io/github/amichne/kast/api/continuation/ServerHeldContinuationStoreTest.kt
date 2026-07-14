package io.github.amichne.kast.api.continuation

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerHeldContinuationStoreTest {
    @Test
    fun `public access binds projection to the store and never returns the state role`() {
        val accessMethods = ServerHeldContinuationStore::class.java.declaredMethods
            .filter { method -> method.name == "lease" || method.name == "consume" }

        assertEquals(setOf("lease", "consume"), accessMethods.mapTo(mutableSetOf()) { it.name })
        assertTrue(accessMethods.all { method -> method.typeParameters.isEmpty() })
        assertTrue(accessMethods.none { method -> method.genericReturnType.typeName.contains("State") })

        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer { },
        )
        val token = store.issueToken("query", TestState("owned"))
        val projected: ContinuationLeaseResult<String> = store.lease(token, "query") { state ->
            state.name
        }

        assertEquals(ContinuationLeaseResult.Granted("owned"), projected)
        store.close()
    }

    @Test
    fun `complete consumes the token and disposes owned state exactly once`() {
        val issuer = IncrementingTokenIssuer()
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = issuer,
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("owned")
        val token = when (val issued = store.issue(query = "query", state = state)) {
            is ContinuationIssueResult.Issued -> issued.token
            is ContinuationIssueResult.Rejected -> error("Issue was rejected: ${issued.failure}")
        }

        val result = store.consume(token, query = "query") { borrowed ->
            assertSame(state, borrowed)
            ContinuationTransition.Complete("done")
        }

        assertEquals(ContinuationConsumeResult.Completed("done"), result)
        assertEquals(listOf(state), disposed)
        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.consume(token, query = "query") { ContinuationTransition.Complete("unexpected") },
        )

        store.close()
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `lease is callback scoped and reusable without transferring state ownership`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("leased")
        val token = store.issueToken(query = "query", state = state)

        val first = store.lease(token, query = "query") { borrowed -> borrowed.name }
        val second = store.lease(token, query = "query") { borrowed -> borrowed.name.length.toString() }

        assertEquals(ContinuationLeaseResult.Granted("leased"), first)
        assertEquals(ContinuationLeaseResult.Granted("6"), second)
        assertEquals(emptyList<TestState>(), disposed)

        assertEquals(
            ContinuationConsumeResult.Completed("complete"),
            store.consume(token, query = "query") { ContinuationTransition.Complete("complete") },
        )
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `lease clears its claim before republishing the reusable token`() {
        val clock = FakeClock()
        val disposed = mutableListOf<TestState>()
        val evictionStarted = CountDownLatch(1)
        val releaseEviction = CountDownLatch(1)
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer { state ->
                if (state.name == "oldest") {
                    evictionStarted.countDown()
                    assertTrue(releaseEviction.await(5, TimeUnit.SECONDS))
                }
                synchronized(disposed) { disposed += state }
            },
            clock = clock,
        )
        store.issueToken("oldest", TestState("oldest"))
        clock.advanceNanos(1)
        val leasedToken = store.issueToken("leased", TestState("leased"))
        val firstResult = AtomicReference<ContinuationLeaseResult<String>>()
        val firstLease = thread(name = "first-continuation-lease", isDaemon = true) {
            firstResult.set(
                store.lease(leasedToken, "leased") { state ->
                    callbackStarted.countDown()
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                    state.name
                },
            )
        }
        assertTrue(callbackStarted.await(5, TimeUnit.SECONDS))
        clock.advanceNanos(1)
        store.issueToken("newest", TestState("newest"))
        releaseCallback.countDown()
        assertTrue(evictionStarted.await(5, TimeUnit.SECONDS))

        try {
            assertEquals(
                ContinuationLeaseResult.Granted("leased"),
                store.lease(leasedToken, "leased") { state -> state.name },
            )
        } finally {
            releaseEviction.countDown()
        }
        firstLease.join(5_000)

        assertFalse(firstLease.isAlive)
        assertEquals(ContinuationLeaseResult.Granted("leased"), firstResult.get())
        store.close()
        assertEquals(3, synchronized(disposed) { disposed.size })
    }

    @Test
    fun `close waits for an admitted consume and terminalizes racing reissue`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer { state ->
                synchronized(disposed) { disposed += state }
            },
        )
        val claimed = TestState("claimed")
        val late = TestState("late")
        val token = store.issueToken(query = "query", state = claimed)
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val consumeResult = AtomicReference<ContinuationConsumeResult<TestToken, String>>()

        val consumer = thread(name = "continuation-consumer") {
            consumeResult.set(
                store.consume(token, query = "query") {
                    actionStarted.countDown()
                    assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
                    ContinuationTransition.Reissue("page", nextQuery = "next")
                },
            )
        }
        assertTrue(actionStarted.await(5, TimeUnit.SECONDS))
        val closer = thread(name = "continuation-closer") {
            closeStarted.countDown()
            store.close()
            closeCompleted.countDown()
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))

        assertEquals(
            ContinuationIssueResult.Rejected(ContinuationAccessFailure.StoreClosed),
            store.issue(query = "late", state = late),
        )
        releaseAction.countDown()
        assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
        consumer.join(5_000)
        closer.join(5_000)

        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed),
            consumeResult.get(),
        )
        assertEquals(setOf(claimed, late), synchronized(disposed) { disposed.toSet() })
        assertEquals(2, synchronized(disposed) { disposed.size })
    }

    @Test
    fun `explicit invalidation disposes once and returns a typed outcome`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("invalidated")
        val token = store.issueToken(query = "query", state = state)

        assertEquals(ContinuationInvalidationResult.Invalidated, store.invalidate(token))
        assertEquals(listOf(state), disposed)
        assertEquals(
            ContinuationInvalidationResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.invalidate(token),
        )

        store.close()
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `abandoned state expires passively without a later store operation`() {
        val disposeCount = AtomicInteger()
        val expired = CountDownLatch(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMillis(50)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer {
                disposeCount.incrementAndGet()
                expired.countDown()
            },
        )
        store.issueToken(query = "query", state = TestState("abandoned"))

        assertTrue(expired.await(5, TimeUnit.SECONDS), "continuation was not passively expired")
        assertEquals(1, disposeCount.get())

        store.close()
        assertEquals(1, disposeCount.get())
    }

    @Test
    fun `close waits for passive expiry disposal already in progress`() {
        val disposalStarted = CountDownLatch(1)
        val releaseDisposal = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMillis(20)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer {
                disposalStarted.countDown()
                assertTrue(releaseDisposal.await(5, TimeUnit.SECONDS))
            },
        )
        store.issueToken("query", TestState("expiring"))
        assertTrue(disposalStarted.await(5, TimeUnit.SECONDS))
        val closer = thread(name = "passive-expiry-closer", isDaemon = true) {
            store.close()
            closeCompleted.countDown()
        }

        try {
            assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseDisposal.countDown()
        }
        assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
        closer.join(5_000)
        assertFalse(closer.isAlive)
    }

    @Test
    fun `reissue moves the same state behind a fresh token until completion`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("paged")
        val firstToken = store.issueToken(query = "first", state = state)

        val firstPage = store.consume(firstToken, query = "first") { borrowed ->
            assertSame(state, borrowed)
            ContinuationTransition.Reissue("page-one", nextQuery = "second")
        }
        val secondToken = when (firstPage) {
            is ContinuationConsumeResult.Reissued -> firstPage.token
            is ContinuationConsumeResult.Completed -> error("Continuation completed early")
            is ContinuationConsumeResult.Rejected -> error("Continuation was rejected: ${firstPage.failure}")
        }

        assertNotEquals(firstToken, secondToken)
        assertEquals(emptyList<TestState>(), disposed)
        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.consume(firstToken, query = "first") { ContinuationTransition.Complete("unexpected") },
        )
        assertEquals(
            ContinuationConsumeResult.Completed("page-two"),
            store.consume(secondToken, query = "second") { borrowed ->
                assertSame(state, borrowed)
                ContinuationTransition.Complete("page-two")
            },
        )
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `query mismatch disposes state without invoking the callback`() {
        val disposed = mutableListOf<TestState>()
        val callbackInvoked = AtomicBoolean(false)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("mismatched")
        val token = store.issueToken(query = "expected", state = state)

        assertEquals(
            ContinuationLeaseResult.Rejected(ContinuationAccessFailure.QueryMismatch),
            store.lease(token, query = "different") {
                callbackInvoked.set(true)
                "unexpected"
            },
        )
        assertFalse(callbackInvoked.get())
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `callback failure disposes state and leaves the consumed token unknown`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("failed")
        val token = store.issueToken(query = "query", state = state)
        val expected = IllegalStateException("callback failed")

        val actual = assertThrows(IllegalStateException::class.java) {
            store.consume(token, query = "query") { throw expected }
        }

        assertSame(expected, actual)
        assertEquals(listOf(state), disposed)
        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.consume(token, query = "query") { ContinuationTransition.Complete("unexpected") },
        )
    }

    @Test
    fun `replacement and capacity eviction dispose in deterministic ownership order`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 2, 1, 3),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val replaced = TestState("replaced")
        val oldest = TestState("oldest")
        val replacement = TestState("replacement")
        val newest = TestState("newest")

        store.issueToken("first", replaced)
        store.issueToken("second", oldest)
        store.issueToken("replacement", replacement)
        store.issueToken("newest", newest)

        assertEquals(listOf(replaced, oldest), disposed)
        store.close()
        assertEquals(listOf(replaced, oldest, replacement, newest), disposed)
    }

    @Test
    fun `capacity evicts the oldest expiry after a lease reinsert`() {
        val clock = FakeClock()
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
            clock = clock,
        )
        val oldest = TestState("oldest-expiry")
        val newer = TestState("newer-expiry")
        val newest = TestState("newest-expiry")
        val oldestToken = store.issueToken("oldest", oldest)
        clock.advanceNanos(1)
        store.issueToken("newer", newer)

        assertEquals(
            ContinuationLeaseResult.Granted("oldest-expiry"),
            store.lease(oldestToken, "oldest") { state -> state.name },
        )
        clock.advanceNanos(1)
        store.issueToken("newest", newest)

        assertEquals(listOf(oldest), disposed)
        store.close()
        assertEquals(setOf(oldest, newer, newest), disposed.toSet())
        assertEquals(3, disposed.size)
    }

    @Test
    fun `issuer collision with a leased token rejects and disposes the offered state`() {
        val disposed = mutableListOf<TestState>()
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 1),
            stateDisposer = ContinuationStateDisposer { state ->
                synchronized(disposed) { disposed += state }
            },
        )
        val retained = TestState("retained")
        val offered = TestState("offered")
        val token = store.issueToken("retained", retained)
        val lease = thread(name = "collision-lease", isDaemon = true) {
            store.lease(token, "retained") { state ->
                callbackStarted.countDown()
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                state.name
            }
        }
        assertTrue(callbackStarted.await(5, TimeUnit.SECONDS))

        try {
            assertEquals(
                ContinuationIssueResult.Rejected(ContinuationAccessFailure.TokenCollision),
                store.issue("collision", offered),
            )
            assertEquals(listOf(offered), synchronized(disposed) { disposed.toList() })
        } finally {
            releaseCallback.countDown()
        }
        lease.join(5_000)
        assertFalse(lease.isAlive)
        store.close()
        assertEquals(setOf(offered, retained), synchronized(disposed) { disposed.toSet() })
        assertEquals(2, synchronized(disposed) { disposed.size })
    }

    @Test
    fun `reissue collision is terminal and cannot strand close`() {
        val disposed = mutableListOf<TestState>()
        val closeCompleted = CountDownLatch(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 1),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("collision")
        val token = store.issueToken("query", state)

        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.TokenCollision),
            store.consume(token, "query") {
                ContinuationTransition.Reissue("page", nextQuery = "next")
            },
        )
        assertEquals(listOf(state), disposed)
        val closer = thread(name = "collision-closer", isDaemon = true) {
            store.close()
            closeCompleted.countDown()
        }

        assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
        closer.join(5_000)
        assertFalse(closer.isAlive)
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `close drains every state when disposers throw and remains idempotent`() {
        val disposed = mutableListOf<TestState>()
        val firstFailure = IllegalStateException("first dispose failed")
        val secondFailure = IllegalStateException("second dispose failed")
        val store = ServerHeldContinuationStore<TestToken, String, TestState, String>(
            capacity = ContinuationCapacity.of(3),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer { state ->
                disposed += state
                when (state.name) {
                    "first" -> throw firstFailure
                    "second" -> throw secondFailure
                }
            },
        )
        val states = listOf(TestState("first"), TestState("second"), TestState("third"))
        states.forEach { state -> store.issueToken(state.name, state) }

        val actual = assertThrows(IllegalStateException::class.java, store::close)

        assertSame(firstFailure, actual)
        assertEquals(listOf(secondFailure), actual.suppressed.toList())
        assertEquals(states, disposed)
        assertThrows(IllegalStateException::class.java, store::close)
        assertEquals(states, disposed)
    }

    private fun ServerHeldContinuationStore<TestToken, String, TestState, String>.issueToken(
        query: String,
        state: TestState,
    ): TestToken = when (val issued = issue(query, state)) {
        is ContinuationIssueResult.Issued -> issued.token
        is ContinuationIssueResult.Rejected -> error("Issue was rejected: ${issued.failure}")
    }

    private data class TestState(val name: String)

    @JvmInline
    private value class TestToken(val value: Int)

    private class IncrementingTokenIssuer : ContinuationTokenIssuer<TestToken> {
        private var next = 0

        override fun issue(): TestToken = TestToken(next++)
    }

    private class ScriptedTokenIssuer(vararg tokens: Int) : ContinuationTokenIssuer<TestToken> {
        private val tokens = tokens.iterator()

        override fun issue(): TestToken = TestToken(tokens.nextInt())
    }

    private class FakeClock : ContinuationClock {
        private var nowNanos = 0L

        override fun nowNanos(): Long = nowNanos

        fun advanceNanos(nanoseconds: Long) {
            nowNanos = Math.addExact(nowNanos, nanoseconds)
        }
    }
}
