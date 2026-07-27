package io.github.amichne.kast.api.continuation

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal interface ServerHeldContinuationStoreConcurrencyTests {
    @Test
    fun `throwing replacement makes reissue terminal and disposes claimed state`() {
        val disposed = mutableListOf<TestState>()
        val replacementFailure = IllegalStateException("replacement disposal failed")
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 2, 2),
            stateDisposer = ContinuationStateDisposer { state ->
                disposed += state
                if (state.name == "replaced") throw replacementFailure
            },
        )
        val claimed = TestState("claimed")
        val replaced = TestState("replaced")
        val claimedToken = store.issueToken("claimed", claimed)
        store.issueToken("replaced", replaced)

        val actual = assertThrows(IllegalStateException::class.java) {
            store.consume(claimedToken, "claimed") {
                ContinuationTransition.Reissue(TestProjection("page"), "next")
            }
        }

        assertSame(replacementFailure, actual)
        assertEquals(listOf(replaced, claimed), disposed)
        assertEquals(
            ContinuationLeaseResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.lease(TestToken(2), "next") { TestProjection("leaked") },
        )
        store.close()
        assertEquals(listOf(replaced, claimed), disposed)
    }

    @Test
    fun `reissue cleanup may close the store reentrantly without awaiting its own publication`() {
        val disposed = mutableListOf<TestState>()
        val cleanupStarted = CountDownLatch(1)
        val reentrantCloseReturned = CountDownLatch(1)
        val operationCompleted = CountDownLatch(1)
        val storeReference = AtomicReference<
            ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>
        >()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = ScriptedTokenIssuer(1, 2, 2),
            stateDisposer = ContinuationStateDisposer { state ->
                synchronized(disposed) { disposed += state }
                if (state.name == "replaced") {
                    cleanupStarted.countDown()
                    storeReference.get().close()
                    reentrantCloseReturned.countDown()
                }
            },
        )
        storeReference.set(store)
        val claimedToken = store.issueToken("claimed", TestState("claimed"))
        store.issueToken("replaced", TestState("replaced"))
        val result = AtomicReference<ContinuationConsumeResult<TestToken, TestProjection>>()
        val failure = AtomicReference<Throwable?>()
        val consumer = thread(name = "reentrant-close-reissue", isDaemon = true) {
            try {
                result.set(
                    store.consume(claimedToken, "claimed") {
                        ContinuationTransition.Reissue(TestProjection("page"), "next")
                    },
                )
            } catch (actual: Throwable) {
                failure.set(actual)
            } finally {
                operationCompleted.countDown()
            }
        }

        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
        assertTrue(reentrantCloseReturned.await(5, TimeUnit.SECONDS))
        assertTrue(operationCompleted.await(5, TimeUnit.SECONDS))
        consumer.join(5_000)

        assertFalse(consumer.isAlive)
        assertEquals(null, failure.get())
        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed),
            result.get(),
        )
        store.close()
        assertEquals(
            listOf(TestState("replaced"), TestState("claimed")),
            synchronized(disposed) { disposed.toList() },
        )
    }

    @Test
    fun `issuer collision with a leased token rejects and disposes the offered state`() {
        val disposed = mutableListOf<TestState>()
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
                TestProjection(state.name)
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
                ContinuationTransition.Reissue(TestProjection("page"), nextQuery = "next")
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
}
