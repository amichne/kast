package io.github.amichne.kast.api.continuation

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal interface ServerHeldContinuationStoreDisposalTests {
    @Test
    fun `query mismatch disposes state without invoking the callback`() {
        val disposed = mutableListOf<TestState>()
        val callbackInvoked = AtomicBoolean(false)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
                TestProjection("unexpected")
            },
        )
        assertFalse(callbackInvoked.get())
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `callback failure disposes state and leaves the consumed token unknown`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
            store.consume(token, query = "query") {
                ContinuationTransition.Complete(TestProjection("unexpected"))
            },
        )
    }

    @Test
    fun `replacement and capacity eviction dispose in deterministic ownership order`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
            ContinuationLeaseResult.Granted(TestProjection("oldest-expiry")),
            store.lease(oldestToken, "oldest") { state -> TestProjection(state.name) },
        )
        clock.advanceNanos(1)
        store.issueToken("newest", newest)

        assertEquals(listOf(oldest), disposed)
        store.close()
        assertEquals(setOf(oldest, newer, newest), disposed.toSet())
        assertEquals(3, disposed.size)
    }

    @Test
    fun `throwing eviction rolls back and disposes the undisclosed issued state`() {
        val disposed = mutableListOf<TestState>()
        val evictionFailure = IllegalStateException("eviction disposal failed")
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 2),
            stateDisposer = ContinuationStateDisposer { state ->
                disposed += state
                if (state.name == "evicted") throw evictionFailure
            },
        )
        val evicted = TestState("evicted")
        val rolledBack = TestState("rolled-back")
        store.issueToken("first", evicted)

        val actual = assertThrows(IllegalStateException::class.java) {
            store.issue("second", rolledBack)
        }

        assertSame(evictionFailure, actual)
        assertEquals(listOf(evicted, rolledBack), disposed)
        assertEquals(
            ContinuationLeaseResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.lease(TestToken(2), "second") { TestProjection("leaked") },
        )
        store.close()
        assertEquals(listOf(evicted, rolledBack), disposed)
    }

    @Test
    fun `issue cleanup may close the store reentrantly without awaiting its own publication`() {
        val disposed = mutableListOf<TestState>()
        val cleanupStarted = CountDownLatch(1)
        val reentrantCloseReturned = CountDownLatch(1)
        val operationCompleted = CountDownLatch(1)
        val storeReference = AtomicReference<
            ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>
        >()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 2),
            stateDisposer = ContinuationStateDisposer { state ->
                synchronized(disposed) { disposed += state }
                if (state.name == "evicted") {
                    cleanupStarted.countDown()
                    storeReference.get().close()
                    reentrantCloseReturned.countDown()
                }
            },
        )
        storeReference.set(store)
        store.issueToken("first", TestState("evicted"))
        val result = AtomicReference<ContinuationIssueResult<TestToken>>()
        val failure = AtomicReference<Throwable?>()
        val issuer = thread(name = "reentrant-close-issue", isDaemon = true) {
            try {
                result.set(store.issue("second", TestState("pending")))
            } catch (actual: Throwable) {
                failure.set(actual)
            } finally {
                operationCompleted.countDown()
            }
        }

        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
        assertTrue(reentrantCloseReturned.await(5, TimeUnit.SECONDS))
        assertTrue(operationCompleted.await(5, TimeUnit.SECONDS))
        issuer.join(5_000)

        assertFalse(issuer.isAlive)
        assertEquals(null, failure.get())
        assertEquals(
            ContinuationIssueResult.Rejected(ContinuationAccessFailure.StoreClosed),
            result.get(),
        )
        store.close()
        assertEquals(
            listOf(TestState("evicted"), TestState("pending")),
            synchronized(disposed) { disposed.toList() },
        )
    }

    @Test
    fun `throwing expiry rolls back and disposes the undisclosed issued state`() {
        val clock = FakeClock()
        val disposed = mutableListOf<TestState>()
        val expiryFailure = IllegalStateException("expiry disposal failed")
        val ttl = Duration.ofDays(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(ttl),
            tokenIssuer = ScriptedTokenIssuer(1, 2),
            stateDisposer = ContinuationStateDisposer { state ->
                disposed += state
                if (state.name == "expired") throw expiryFailure
            },
            clock = clock,
        )
        val expired = TestState("expired")
        val rolledBack = TestState("rolled-back")
        store.issueToken("first", expired)
        clock.advanceNanos(ttl.toNanos())

        val actual = assertThrows(IllegalStateException::class.java) {
            store.issue("second", rolledBack)
        }

        assertSame(expiryFailure, actual)
        assertEquals(listOf(expired, rolledBack), disposed)
        assertEquals(
            ContinuationLeaseResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.lease(TestToken(2), "second") { TestProjection("leaked") },
        )
        store.close()
        assertEquals(listOf(expired, rolledBack), disposed)
    }
}
