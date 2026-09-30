package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Pure store lifetime tests: no compiler, filesystem, scheduler or native authority claim. */
class QueryStateLifetimeTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(7).refined(),
        )
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(1000).refined(),
        )

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
                assertInstanceOf(OperationOutcome.Rejected::class.java, protocol.execute(input, lease, budget))
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
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(output.token, lease, 1000))
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(result.reference, lease))
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
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
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
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
        assertEquals(QueryCheckpointAcquisition.Unavailable, store.acquireCheckpoint(token, lease, 1000))
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.EXPIRED),
            store.commitPublication(replay.claim, replay.page),
        )
        org.junit.jupiter.api.Assertions.assertTrue(store.retentionMeasurements().retainedBytes.value > 0)
        store.releasePublication(replay.claim)
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(token, lease))
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(result.reference, lease))
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `a younger output cannot extend its older upstream checkpoint lifetime`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val checkpoint = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val completePage = page() as OperationOutcome.Complete
        val parentPage =
            OperationOutcome.Qualified(
                completePage.evidence,
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(0).refined(),
                        listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.Upstream(checkpoint),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
                    .refined(),
            )
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
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(output.token, lease, 1000))
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(checkpoint, lease))
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    private suspend fun qualified(checkpoint: QueryCheckpointDocument): QueryPublishedPage {
        val complete = page() as OperationOutcome.Complete
        return OperationOutcome.Qualified(
            complete.evidence,
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(0).refined(),
                    listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                    QueryQualifiedProgressDocument.Resumable(checkpoint, ReadResumeActionDocument.RESUME),
                )
                .refined(),
        )
    }

    private suspend fun checkpoint(): QueryCheckpoint {
        lateinit var retained: QueryCheckpoint
        CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    retained =
                        object : QueryCheckpoint {
                            override val plan = admitted.plan
                            override val lease = admitted.lease
                            override val retainedBytes = 1024L
                        }
                    complete()
                },
                CanonicalQueryReferences(),
            )
            .execute(request(), lease, budget)
        return retained
    }

    private suspend fun page(reference: QueryResultReference? = null): QueryPublishedPage {
        val page =
            CanonicalQueryProtocol(QueryOperations { complete() }, CanonicalQueryReferences())
                .execute(request(), lease, budget) as OperationOutcome.Complete
        return if (reference == null) page
        else
            OperationOutcome.Complete(
                page.evidence.copy(
                    payload = page.evidence.payload.copy(retention = QueryResultRetention.Retained(reference))
                )
            )
    }

    private fun complete() =
        QueryExecutionResult.Complete(
            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(0).refined()),
        )

    private fun request() =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(text("main"))), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
