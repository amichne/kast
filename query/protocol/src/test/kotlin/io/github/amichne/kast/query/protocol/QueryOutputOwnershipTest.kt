package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryOutputOwnershipTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    private val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(10000).refined(),
        )

    private val largerGrant = ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(200).refined())

    @Test
    fun `checkpoint expiry during an attempt refuses publication and drains reserved ownership`() = runTest {
        var now = 0L
        val store = QueryStateStore(clock = { now }, ttlMillis = 1)
        val issued = store.issueCheckpoint(request(), checkpoint(lease)) as QueryCheckpointIssuance.Issued
        lateinit var claim: QueryExecutionClaim
        lateinit var page: QueryPublishedPage
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { complete() },
                CanonicalQueryReferences(),
                store,
                QueryExecutionPublication { _, active, result ->
                    claim = active
                    page = result
                    QueryExecutionPublicationResult.PREPARED
                },
            )
        protocol.execute(QueryRunRequest.Resume(issued.token), lease, budget)
        now = 1_000_001L
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.EXPIRED),
            store.commitPublication(claim, page),
        )
        store.releasePublication(claim)
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(issued.token, lease))
        assertEquals(256L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `replay capacity is rejected before semantic execution`() = runTest {
        val store = QueryStateStore(maximumBytes = 16_384L)
        val issued = store.issueCheckpoint(request(), checkpoint(lease)) as QueryCheckpointIssuance.Issued
        var calls = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    complete()
                },
                CanonicalQueryReferences(),
                store,
            )
        val result = protocol.execute(QueryRunRequest.Resume(issued.token), lease, budget) as OperationOutcome.Rejected
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryRunRejection.ExecutionRejected(
                io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_CAPACITY_EXCEEDED
            ),
            result.reason,
        )
        assertEquals(0, calls)
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restoreCheckpoint(issued.token, lease))
    }

    @Test
    fun `output suffix uses the same hidden ownership quota and retirement as pipeline state`() = runTest {
        val store = QueryStateStore()
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val page =
            CanonicalQueryProtocol(QueryOperations { complete() }, CanonicalQueryReferences())
                .execute(request(), lease, budget)
        val issued =
            store.issueOutput(request(), lease, page, initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(issued.token, lease, 10000))
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, page.advertisingOutput(issued.token)),
        )
        val consumed = store.acquireOutput(issued.token, lease, 10000) as QueryOutputAcquisition.Acquired
        assertEquals(page, consumed.page)
        assertEquals(QueryOutputAcquisition.InUse, store.acquireOutput(issued.token, lease, 10000))
        assertEquals(
            QueryOutputIssuance.NonAdvancing,
            store.issueOutput(request(), lease, page, consumed.claim, QueryRetentionByteCount.parse(512).refined()),
        )
        store.releasePublication(consumed.claim)
        val retry = store.acquireOutput(issued.token, lease, 10000) as QueryOutputAcquisition.Acquired
        store.retire()
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.OWNER_RETIRED),
            store.commitPublication(retry.claim, retry.page),
        )
        store.releasePublication(retry.claim)
        assertEquals(
            QueryOutputAcquisition.Rejected(QueryContinuationFailure.OWNER_RETIRED),
            store.acquireOutput(issued.token, lease, 10000),
        )
        assertEquals(256L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `published output page replays without invoking semantic execution`() = runTest {
        val store = QueryStateStore()
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val page =
            CanonicalQueryProtocol(QueryOperations { complete() }, CanonicalQueryReferences())
                .execute(request(), lease, budget)
        val issued =
            store.issueOutput(request(), lease, page, initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        store.commitPublication(initial, page.advertisingOutput(issued.token))
        var calls = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    complete()
                },
                CanonicalQueryReferences(),
                store,
            )
        val first = protocol.execute(QueryRunRequest.Resume(issued.token), lease, budget)
        val replay = protocol.execute(QueryRunRequest.Resume(issued.token), lease, budget)
        assertEquals(first, replay)
        assertEquals(page, replay)
        assertEquals(0, calls)
        val claim = (store.acquireOutput(issued.token, lease, 10000) as QueryOutputAcquisition.Published).claim
        assertEquals(
            QueryOutputIssuance.PublishedPageImmutable,
            store.issueOutput(request(), lease, page, claim, QueryRetentionByteCount.parse(512).refined()),
        )
        store.releasePublication(claim)
    }

    @Test
    fun `discarded initial attempt revokes its detached output suffix`() = runTest {
        val store = QueryStateStore()
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val page =
            CanonicalQueryProtocol(QueryOperations { complete() }, CanonicalQueryReferences())
                .execute(request(), lease, budget)
        val issued =
            store.issueOutput(request(), lease, page, initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        store.releasePublication(initial)
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(issued.token, lease, 10000))
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.CLAIM_UNAVAILABLE),
            store.commitPublication(initial, page),
        )
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    private suspend fun checkpoint(authority: SemanticReadAuthority): QueryCheckpoint {
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
            .execute(request(), authority, budget)
        return retained
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
                    QueryScopeDocument(bounded(listOf(text("main"), text("test"))), null, null),
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

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure =
        when (this) {
            is Refinement.Refined -> error("Expected rejection")
            is Refinement.Rejected -> failure
        }
}
