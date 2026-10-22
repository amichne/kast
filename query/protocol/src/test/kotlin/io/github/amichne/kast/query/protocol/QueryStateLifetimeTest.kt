package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryRetainedResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Pure store lifetime tests: no compiler, filesystem, scheduler or native authority claim. */
class QueryStateLifetimeTest : QueryStateLifetimeCase() {
    @Test
    fun `initial result input stays pinned through eviction expiry and release`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 2, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val captured = QueryRetainedResult.capture(lease, complete()).refined()
        val input = (store.issueResult(request(), captured) as QueryResultIssuance.Issued).reference
        val unrelated = (store.issueResult(request(), captured) as QueryResultIssuance.Issued).reference
        now = 5_000_000L
        val claim = (store.acquireInitial(lease, setOf(input)) as QueryInitialAcquisition.Acquired).claim
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(input, lease, claim))
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(unrelated, lease))
        assertEquals(QueryResultIssuance.CapacityExceeded, store.issueResult(request(), captured))
        now = 11_000_000L
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(input, lease, claim))
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(input, lease))
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.EXPIRED),
            store.commitPublication(claim, page()),
        )
        store.releasePublication(claim)
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(input, lease))
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `an active input pin cannot admit an expired result to a new request`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 3, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val reference =
            (store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined())
                    as QueryResultIssuance.Issued)
                .reference
        now = 5_000_000L
        val claim = (store.acquireInitial(lease, setOf(reference)) as QueryInitialAcquisition.Acquired).claim
        now = 11_000_000L
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Expired input must reject before semantic effects") },
                CanonicalQueryReferences(),
                store,
            )
        val requests =
            listOf(
                QueryRunRequest.ReadResult.symbols(
                    reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                request().copy(from = QueryFromDocument.Result(reference)),
            )
        for (input in requests) {
            val rejected =
                assertInstanceOf(OperationOutcome.Rejected::class.java, protocol.executePage(input, lease, budget))
            assertEquals(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
                rejected.reason,
            )
        }
        store.releasePublication(claim)
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(reference, lease))
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `a younger retained output expires with its oldest dependency`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val result =
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined())
                as QueryResultIssuance.Issued
        val page = page(result.reference)
        now = 5_000_000L
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(request(), lease, page, initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, page.advertisingOutput(output.token)),
        )
        store.releasePublication(initial)

        now = 9_999_999L
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(result.reference, lease))
        val acquired =
            assertInstanceOf(
                QueryOutputAcquisition.Acquired::class.java,
                store.acquireOutput(output.token, lease, 1000),
            )
        assertEquals(page, acquired.page)
        store.releasePublication(acquired.claim)

        now = 10_000_001L
        assertEquals(
            QueryOutputAcquisition.Rejected(QueryContinuationFailure.DEPENDENCY_UNAVAILABLE),
            store.acquireOutput(output.token, lease, 1000),
        )
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(result.reference, lease))
        assertEquals(256L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `a younger published checkpoint expires with its oldest dependency`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val result =
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined())
                as QueryResultIssuance.Issued
        val page = page(result.reference)
        now = 5_000_000L
        val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val producer = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, page))
        store.releasePublication(producer.claim)

        now = 9_999_999L
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(result.reference, lease))
        val replay =
            assertInstanceOf(
                QueryCheckpointAcquisition.Published::class.java,
                store.acquireCheckpoint(token, lease, 1000),
            )
        assertEquals(page, replay.page)
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(replay.claim, replay.page))
        store.releasePublication(replay.claim)

        now = 10_000_001L
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(token, lease))
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(result.reference, lease))
        assertEquals(256L, store.retentionMeasurements().retainedBytes.value)
        assertEquals(
            QueryCheckpointAcquisition.Rejected(QueryContinuationFailure.DEPENDENCY_UNAVAILABLE),
            store.acquireCheckpoint(token, lease, 1000),
        )
    }

    @Test
    fun `a replay claim pins its expired dependency graph until cancellation releases ownership`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val result =
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined())
                as QueryResultIssuance.Issued
        val page = page(result.reference)
        now = 5_000_000L
        val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val producer = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired
        store.commitPublication(producer.claim, page)
        store.releasePublication(producer.claim)
        val replay = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Published

        now = 16_000_000L
        assertEquals(page, replay.page)
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(result.reference, lease))
        assertEquals(
            QueryCheckpointAcquisition.Rejected(QueryContinuationFailure.EXPIRED),
            store.acquireCheckpoint(token, lease, 1000),
        )
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.EXPIRED),
            store.commitPublication(replay.claim, replay.page),
        )
        org.junit.jupiter.api.Assertions.assertTrue(store.retentionMeasurements().retainedBytes.value > 0)
        store.releasePublication(replay.claim)
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(token, lease))
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(result.reference, lease))
        assertEquals(256L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `a younger output cannot extend its older upstream checkpoint lifetime`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val checkpoint = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val completePage = page() as OperationOutcome.Complete
        val parentPage = qualified(QueryCheckpointDocument.Upstream(checkpoint))
        now = 5_000_000L
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(request(), lease, parentPage, initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, parentPage.advertisingOutput(output.token)),
        )
        store.releasePublication(initial)

        now = 9_999_999L
        val producer =
            assertInstanceOf(
                QueryCheckpointAcquisition.Acquired::class.java,
                store.acquireCheckpoint(checkpoint, lease, 1000),
            )
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, completePage))
        store.releasePublication(producer.claim)
        val replay =
            assertInstanceOf(
                QueryCheckpointAcquisition.Published::class.java,
                store.acquireCheckpoint(checkpoint, lease, 1000),
            )
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(replay.claim, completePage))
        store.releasePublication(replay.claim)

        now = 10_000_001L
        assertEquals(
            QueryOutputAcquisition.Rejected(QueryContinuationFailure.DEPENDENCY_UNAVAILABLE),
            store.acquireOutput(output.token, lease, 1000),
        )
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(checkpoint, lease))
        assertEquals(512L, store.retentionMeasurements().retainedBytes.value)
    }
}
