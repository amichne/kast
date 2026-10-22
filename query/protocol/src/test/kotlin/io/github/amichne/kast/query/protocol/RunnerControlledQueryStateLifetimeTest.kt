package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.query.contract.QueryRetainedResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Pure store lifetime tests: no compiler, filesystem, scheduler or native authority claim. */
class RunnerControlledQueryStateLifetimeTest : QueryStateLifetimeCase() {
    @Test
    fun `only the private runner owner can acquire its expired successor and release its pin`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 1, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val invocation = QueryInvocationPublication()
        val token = issuePrivateCheckpoint(store, invocation)
        now = 20_000_000L
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertEquals(
            QueryResultIssuance.CapacityExceeded,
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
        )
        assertEquals(
            QueryCheckpointAcquisition.Rejected(QueryContinuationFailure.EXPIRED),
            store.acquireCheckpoint(token, lease, 1000),
        )
        assertEquals(
            QueryCheckpointAcquisition.Rejected(QueryContinuationFailure.EXPIRED),
            store.acquireCheckpoint(
                token = token,
                lease = lease,
                maximumPageBytes = 1000,
                invocation = QueryInvocationPublication(),
            ),
        )
        val acquired =
            assertInstanceOf(
                QueryCheckpointAcquisition.Acquired::class.java,
                store.acquireCheckpoint(
                    token = token,
                    lease = lease,
                    maximumPageBytes = 1000,
                    invocation = invocation,
                ),
            )
        store.releasePublication(acquired.claim)
        store.finishInvocation(invocation, null)
        assertEquals(0, store.retentionMeasurements().retainedEntries.value)
    }

    @Test
    fun `runner input claim keeps admitted evidence after TTL without renewing the external token`() = runTest {
        var now = 0L
        val store = QueryStateStore(clock = { now }, ttlMillis = 10)
        val issued =
            assertInstanceOf(
                QueryResultIssuance.Issued::class.java,
                store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
            )
        val acquired =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(
                    lease,
                    setOf(issued.reference),
                    io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED,
                ),
            )
        now = 20_000_000L
        assertInstanceOf(
            QueryResultRestoration.Restored::class.java,
            store.restoreResult(issued.reference, lease, acquired.claim),
        )
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease))
        store.releasePublication(acquired.claim)
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease))
    }

    private suspend fun issuePrivateCheckpoint(
        store: QueryStateStore,
        invocation: QueryInvocationPublication,
    ): io.github.amichne.kast.protocol.contract.QueryExecutionContinuation.Pipeline {
        val runner =
            request()
                .copy(
                    executionMode =
                        io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
                )
        val initial =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(lease, executionMode = runner.executionMode),
            )
        val token =
            assertInstanceOf(
                    QueryCheckpointIssuance.Issued::class.java,
                    store.issueCheckpoint(runner, checkpoint(), publicationOwner = initial.claim),
                )
                .token
        assertEquals(
            QueryExecutionPublicationResult.COMMITTED,
            invocation.prepare(store, initial.claim, qualified(QueryCheckpointDocument.Upstream(token))),
        )
        store.releasePublication(initial.claim)
        return token
    }
}
