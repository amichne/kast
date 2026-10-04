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
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunQualification
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Pure retained-store transitions only; no compiler, filesystem, scheduler or native semantic claim. */
class QueryStateDependencyAdmissionTest {
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
    fun `direct checkpoint restoration cannot bypass its active execution claim`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val running = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired
        try {
            assertEquals(QueryCheckpointAcquisition.InUse, store.acquireCheckpoint(token, lease, 1000))
            assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(token, lease))
        } finally {
            store.releasePublication(running.claim)
        }
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restoreCheckpoint(token, lease))
    }

    @Test
    fun `exact cached publication remains replayable while its public successor is busy`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val child = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val parent = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val page = qualified(QueryCheckpointDocument.Upstream(child))
        val producer = store.acquireCheckpoint(parent, lease, 1000) as QueryCheckpointAcquisition.Acquired
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, page))
        store.releasePublication(producer.claim)
        val busy = store.acquireCheckpoint(child, lease, 1000) as QueryCheckpointAcquisition.Acquired
        try {
            val replay =
                assertInstanceOf(
                    QueryCheckpointAcquisition.Published::class.java,
                    store.acquireCheckpoint(parent, lease, 1000),
                )
            try {
                assertEquals(page, replay.page)
                assertEquals(QueryPublicationCommit.Committed, store.commitPublication(replay.claim, replay.page))
                assertEquals(QueryCheckpointAcquisition.InUse, store.acquireCheckpoint(child, lease, 1000))
            } finally {
                store.releasePublication(replay.claim)
            }
        } finally {
            store.releasePublication(busy.claim)
        }
    }

    @Test
    fun `output dedup cannot reuse a ready token whose pinned transitive dependency expired`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 16, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val retained =
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined())
                as QueryResultIssuance.Issued
        val suffix = page(retained.reference)
        now = 5_000_000L
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val oldOutput = store.issueOutput(request(), lease, suffix, initial, bytes()) as QueryOutputIssuance.Issued
        val prefix = suffix.advertisingOutput(oldOutput.token)
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(initial, prefix))
        store.releasePublication(initial)
        val parent = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val producer = store.acquireCheckpoint(parent, lease, 1000) as QueryCheckpointAcquisition.Acquired
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, prefix))
        store.releasePublication(producer.claim)
        now = 9_000_000L
        val pin = store.acquireCheckpoint(parent, lease, 1000) as QueryCheckpointAcquisition.Published
        now = 11_000_000L
        val fresh = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        try {
            val successor = store.issueOutput(request(), lease, suffix, fresh, bytes()) as QueryOutputIssuance.Issued
            assertNotEquals(oldOutput.token, successor.token)
            assertEquals(
                QueryPublicationCommit.Rejected(QueryPublicationFailure.EXPIRED),
                store.commitPublication(fresh, suffix.advertisingOutput(successor.token)),
            )
        } finally {
            store.releasePublication(fresh)
            store.releasePublication(pin.claim)
        }
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `output dedup cannot reuse its own staged token with a missing dependency`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val claim = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val missing = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()
        val suffix = page(missing)
        try {
            val first = store.issueOutput(request(), lease, suffix, claim, bytes()) as QueryOutputIssuance.Issued
            val second = store.issueOutput(request(), lease, suffix, claim, bytes()) as QueryOutputIssuance.Issued
            assertNotEquals(first.token, second.token)
            assertEquals(
                QueryPublicationCommit.Rejected(QueryPublicationFailure.DEPENDENCY_UNAVAILABLE),
                store.commitPublication(claim, suffix.advertisingOutput(second.token)),
            )
        } finally {
            store.releasePublication(claim)
        }
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `output dedup cannot reuse a staged token depending on another attempt private result`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val producer = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val retained =
            store.issueResult(
                request(),
                QueryRetainedResult.capture(lease, complete()).refined(),
                publicationOwner = producer,
            ) as QueryResultIssuance.Issued
        val consumer = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val suffix = page(retained.reference)
        try {
            val first = store.issueOutput(request(), lease, suffix, consumer, bytes()) as QueryOutputIssuance.Issued
            val second = store.issueOutput(request(), lease, suffix, consumer, bytes()) as QueryOutputIssuance.Issued
            assertNotEquals(first.token, second.token)
            assertEquals(
                QueryPublicationCommit.Rejected(QueryPublicationFailure.DEPENDENCY_UNAVAILABLE),
                store.commitPublication(consumer, suffix.advertisingOutput(second.token)),
            )
        } finally {
            store.releasePublication(consumer)
            store.releasePublication(producer)
        }
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
        val complete =
            CanonicalQueryProtocol(QueryOperations { complete() }, CanonicalQueryReferences())
                .execute(request(), lease, budget) as OperationOutcome.Complete
        return if (reference == null) complete
        else
            OperationOutcome.Complete(
                complete.evidence.copy(
                    payload = complete.evidence.payload.copy(retention = QueryResultRetention.Retained(reference))
                )
            )
    }

    private fun complete() =
        QueryExecutionResult.Complete.create(
            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(0).refined()),
        )

    private fun request() =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun bytes() = QueryRetentionByteCount.parse(512).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Dependency fixture rejected: $failure")
        }
}
