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
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
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
class QueryContinuationFailureTest {
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
    fun `capacity eviction preserves its precise cause after identity reclamation`() = runTest {
        val store = QueryStateStore(capacity = 2, maximumBytes = 100_000L)
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(request(), lease, page(), initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, page().advertisingOutput(output.token)),
        )
        store.releasePublication(initial)
        assertInstanceOf(
            QueryResultIssuance.Issued::class.java,
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
        )
        assertInstanceOf(
            QueryResultIssuance.Issued::class.java,
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
        )
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Evicted output must reject before semantic effects") },
                CanonicalQueryReferences(),
                store,
            )
        val rejected =
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                protocol.execute(QueryRunRequest.Resume(output.token), lease, budget),
            )
        val reason = assertInstanceOf(QueryRunRejection.ExecutionRejected::class.java, rejected.reason)
        assertEquals("CONTINUATION_EVICTED", reason.reason.name)
        org.junit.jupiter.api.Assertions.assertTrue(store.retentionMeasurements().retainedBytes.value <= 100_000L)
    }

    @Test
    fun `bounded receipt history returns unavailable after its cause is reclaimed`() = runTest {
        val store = QueryStateStore(capacity = 2, maximumBytes = 100_000L)
        val tokens = mutableListOf<io.github.amichne.kast.protocol.contract.QueryExecutionContinuation.Output>()
        repeat(5) { index ->
            val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
            val output =
                store.issueOutput(request(index), lease, page(), initial, QueryRetentionByteCount.parse(512).refined())
                    as QueryOutputIssuance.Issued
            assertEquals(
                QueryPublicationCommit.Committed,
                store.commitPublication(initial, page().advertisingOutput(output.token)),
            )
            store.releasePublication(initial)
            tokens += output.token
        }
        assertEquals(2, store.retentionMeasurements().retainedRevocations.value)
        org.junit.jupiter.api.Assertions.assertTrue(store.retentionMeasurements().retainedBytes.value <= 100_000L)
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(tokens.first(), lease, 1000))
        assertEquals(
            QueryOutputAcquisition.Rejected(QueryContinuationFailure.EVICTED),
            store.acquireOutput(tokens[tokens.lastIndex - 1], lease, 1000),
        )
    }

    @Test
    fun `new epoch trims inherited receipts to admitted quotas`() = runTest {
        val receipts = QueryContinuationRevocations()
        val previous = QueryStateStore(capacity = 2, maximumBytes = 100_000L, revocations = receipts)
        val tokens = mutableListOf<io.github.amichne.kast.protocol.contract.QueryExecutionContinuation.Output>()
        repeat(3) { index ->
            val initial = (previous.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
            val output =
                previous.issueOutput(
                    request(index),
                    lease,
                    page(),
                    initial,
                    QueryRetentionByteCount.parse(512).refined(),
                ) as QueryOutputIssuance.Issued
            assertEquals(
                QueryPublicationCommit.Committed,
                previous.commitPublication(initial, page().advertisingOutput(output.token)),
            )
            previous.releasePublication(initial)
            tokens += output.token
        }
        previous.retire(QueryStateRetirement.BASIS_MOVED)
        val current = QueryStateStore(capacity = 1, maximumBytes = 256L, revocations = receipts)
        assertEquals(0, current.retentionMeasurements().retainedEntries.value)
        assertEquals(1, current.retentionMeasurements().retainedRevocations.value)
        assertEquals(256L, current.retentionMeasurements().retainedBytes.value)
        assertEquals(QueryOutputAcquisition.Unavailable, current.acquireOutput(tokens.first(), lease, 1))
        assertEquals(
            QueryOutputAcquisition.Rejected(QueryContinuationFailure.STALE_BASIS),
            current.acquireOutput(tokens.last(), lease, 1),
        )
    }

    @Test
    fun `expired output reports its exact cause before store expiry removes identity`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(request(), lease, page(), initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, page().advertisingOutput(output.token)),
        )
        store.releasePublication(initial)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Expired output must reject before semantic effects") },
                CanonicalQueryReferences(),
                store,
            )
        now = 10_000_001L
        val rejected =
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                protocol.execute(QueryRunRequest.Resume(output.token), lease, budget),
            )
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_EXPIRED),
            rejected.reason,
        )
        assertEquals(256L, store.retentionMeasurements().retainedBytes.value)
        assertEquals(1, store.retentionMeasurements().retainedRevocations.value)
    }

    @Test
    fun `retired owner reports its cause on resume without semantic effects`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val initial = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(request(), lease, page(), initial, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, page().advertisingOutput(output.token)),
        )
        store.releasePublication(initial)
        store.retire()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Retired owner must reject before semantic effects") },
                CanonicalQueryReferences(),
                store,
            )
        val rejected =
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                protocol.execute(QueryRunRequest.Resume(output.token), lease, budget),
            )
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_OWNER_RETIRED),
            rejected.reason,
        )
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
        QueryExecutionResult.Complete.create(
            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(0).refined()),
        )

    private fun request(index: Int? = null) =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    if (index == null) QueryMatchDocument.All
                    else
                        QueryMatchDocument.Name(
                            text("resource$index"),
                            io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument.EXACT_NAME,
                        ),
                    QueryScopeDocument(bounded(listOf(text("main"))), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
