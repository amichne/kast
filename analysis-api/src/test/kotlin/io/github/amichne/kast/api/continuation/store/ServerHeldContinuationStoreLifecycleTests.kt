package io.github.amichne.kast.api.continuation

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal interface ServerHeldContinuationStoreLifecycleTests {
    @Test
    fun `close waits for an admitted consume and terminalizes racing reissue`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
        val consumeResult = AtomicReference<ContinuationConsumeResult<TestToken, TestProjection>>()

        val consumer = thread(name = "continuation-consumer") {
            consumeResult.set(
                store.consume(token, query = "query") {
                    actionStarted.countDown()
                    assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
                    ContinuationTransition.Reissue(TestProjection("page"), nextQuery = "next")
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("paged")
        val firstToken = store.issueToken(query = "first", state = state)

        val firstPage = store.consume(firstToken, query = "first") { borrowed ->
            assertSame(state, borrowed)
            ContinuationTransition.Reissue(TestProjection("page-one"), nextQuery = "second")
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
            store.consume(firstToken, query = "first") {
                ContinuationTransition.Complete(TestProjection("unexpected"))
            },
        )
        assertEquals(
            ContinuationConsumeResult.Completed(TestProjection("page-two")),
            store.consume(secondToken, query = "second") { borrowed ->
                assertSame(state, borrowed)
                ContinuationTransition.Complete(TestProjection("page-two"))
            },
        )
        assertEquals(listOf(state), disposed)
    }
}
