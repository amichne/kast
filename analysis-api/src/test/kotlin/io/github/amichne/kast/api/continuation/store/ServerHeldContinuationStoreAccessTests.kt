package io.github.amichne.kast.api.continuation

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal interface ServerHeldContinuationStoreAccessTests {
    @Test
    fun `public access binds projection to the store and never returns the state role`() {
        val accessMethods = ServerHeldContinuationStore::class.java.declaredMethods
            .filter { method -> method.name == "lease" || method.name == "consume" }

        assertEquals(setOf("lease", "consume"), accessMethods.mapTo(mutableSetOf()) { it.name })
        assertTrue(accessMethods.all { method -> method.typeParameters.isEmpty() })
        assertTrue(accessMethods.none { method -> method.genericReturnType.typeName.contains("State") })

        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer { },
        )
        val token = store.issueToken("query", TestState("owned"))
        val projected: ContinuationLeaseResult<TestProjection> = store.lease(token, "query") { state ->
            TestProjection(state.name)
        }

        assertEquals(ContinuationLeaseResult.Granted(TestProjection("owned")), projected)
        store.close()
    }

    @Test
    fun `nullable output remains explicit inside a domain projection`() {
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(1),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer { },
        )
        val token = when (val issued = store.issue("query", TestState("owned"))) {
            is ContinuationIssueResult.Issued -> issued.token
            is ContinuationIssueResult.Rejected -> error("Issue was rejected: ${issued.failure}")
        }

        assertEquals(
            ContinuationLeaseResult.Granted(TestProjection(null)),
            store.lease(token, "query") { TestProjection(null) },
        )
        store.close()
    }

    @Test
    fun `complete consumes the token and disposes owned state exactly once`() {
        val issuer = IncrementingTokenIssuer()
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
            ContinuationTransition.Complete(TestProjection("done"))
        }

        assertEquals(ContinuationConsumeResult.Completed(TestProjection("done")), result)
        assertEquals(listOf(state), disposed)
        assertEquals(
            ContinuationConsumeResult.Rejected(ContinuationAccessFailure.UnknownToken),
            store.consume(token, query = "query") {
                ContinuationTransition.Complete(TestProjection("unexpected"))
            },
        )

        store.close()
        assertEquals(listOf(state), disposed)
    }

    @Test
    fun `lease is callback scoped and reusable without transferring state ownership`() {
        val disposed = mutableListOf<TestState>()
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
            capacity = ContinuationCapacity.of(2),
            timeToLive = ContinuationTtl.of(Duration.ofMinutes(1)),
            tokenIssuer = IncrementingTokenIssuer(),
            stateDisposer = ContinuationStateDisposer(disposed::add),
        )
        val state = TestState("leased")
        val token = store.issueToken(query = "query", state = state)

        val first = store.lease(token, query = "query") { borrowed -> TestProjection(borrowed.name) }
        val second = store.lease(token, query = "query") { borrowed ->
            TestProjection(borrowed.name.length.toString())
        }

        assertEquals(ContinuationLeaseResult.Granted(TestProjection("leased")), first)
        assertEquals(ContinuationLeaseResult.Granted(TestProjection("6")), second)
        assertEquals(emptyList<TestState>(), disposed)

        assertEquals(
            ContinuationConsumeResult.Completed(TestProjection("complete")),
            store.consume(token, query = "query") {
                ContinuationTransition.Complete(TestProjection("complete"))
            },
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
        val store = ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>(
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
        val firstResult = AtomicReference<ContinuationLeaseResult<TestProjection>>()
        val firstLease = thread(name = "first-continuation-lease", isDaemon = true) {
            firstResult.set(
                store.lease(leasedToken, "leased") { state ->
                    callbackStarted.countDown()
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                    TestProjection(state.name)
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
                ContinuationLeaseResult.Granted(TestProjection("leased")),
                store.lease(leasedToken, "leased") { state -> TestProjection(state.name) },
            )
        } finally {
            releaseEviction.countDown()
        }
        firstLease.join(5_000)

        assertFalse(firstLease.isAlive)
        assertEquals(ContinuationLeaseResult.Granted(TestProjection("leased")), firstResult.get())
        store.close()
        assertEquals(3, synchronized(disposed) { disposed.size })
    }
}
