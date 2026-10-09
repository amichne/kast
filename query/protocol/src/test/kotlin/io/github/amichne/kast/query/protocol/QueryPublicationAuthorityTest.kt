package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
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
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure store lifetime tests: no compiler, filesystem, scheduler or native authority claim. */
class QueryPublicationAuthorityTest {
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
    fun `a cached entry and its replay claims share the same configured count capacity`() = runTest {
        val store = QueryStateStore(capacity = 2, maximumBytes = 100_000L)
        val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val producer = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired
        val page = page()
        store.commitPublication(producer.claim, page)
        store.releasePublication(producer.claim)
        val before = store.retentionMeasurements()
        val replay = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Published
        val whileClaimed = store.retentionMeasurements()
        assertTrue(whileClaimed.retainedBytes.value > before.retainedBytes.value)
        assertEquals(QueryCheckpointAcquisition.CapacityExceeded, store.acquireCheckpoint(token, lease, 1000))
        assertEquals(whileClaimed.retainedBytes, store.retentionMeasurements().retainedBytes)
        store.releasePublication(replay.claim)
        assertEquals(before.retainedBytes, store.retentionMeasurements().retainedBytes)
        val successorReplay =
            assertInstanceOf(
                QueryCheckpointAcquisition.Published::class.java,
                store.acquireCheckpoint(token, lease, 1000),
            )
        store.releasePublication(successorReplay.claim)
    }

    @Test
    fun `one cached entry under capacity one rejects an additional replay claim without changing retention`() =
        runTest {
            val store = QueryStateStore(capacity = 1, maximumBytes = 100_000L)
            val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
            val producer = store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired
            store.commitPublication(producer.claim, page())
            store.releasePublication(producer.claim)
            val before = store.retentionMeasurements()
            assertEquals(QueryCheckpointAcquisition.CapacityExceeded, store.acquireCheckpoint(token, lease, 1000))
            assertEquals(before, store.retentionMeasurements())
        }

    @Test
    fun `an active producer cannot publish itself as its own successor`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val claim = (store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired).claim
        val proven = page() as OperationOutcome.Complete
        val nonAdvancing =
            OperationOutcome.Qualified(
                proven.evidence,
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(0).refined(),
                        listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.Upstream(token),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
                    .refined(),
            )
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.NON_ADVANCING_SUCCESSOR),
            store.commitPublication(claim, nonAdvancing),
        )
        assertEquals(QueryCheckpointAcquisition.InUse, store.acquireCheckpoint(token, lease, 1000))
        store.releasePublication(claim)
        val retry =
            assertInstanceOf(
                QueryCheckpointAcquisition.Acquired::class.java,
                store.acquireCheckpoint(token, lease, 1000),
            )
        store.releasePublication(retry.claim)
    }

    @Test
    fun `publication cannot replace its authority or operation proof`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val complete = page() as OperationOutcome.Complete
        val wrongBasis =
            OperationOutcome.Complete(
                complete.evidence.copy(
                    basis = io.github.amichne.kast.kernel.EvidenceBasis.Published(EvidenceGeneration.parse(8).refined())
                )
            )
        val wrongOperation =
            OperationOutcome.Complete(complete.evidence.copy(operation = CanonicalOperation.SOURCE_READ.id))
        for (invalid in listOf(wrongBasis, wrongOperation)) {
            val claim = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
            assertEquals(
                QueryPublicationCommit.Rejected(QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH),
                store.commitPublication(claim, invalid),
            )
            store.releasePublication(claim)
            assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
        }
    }

    @Test
    fun `a retained output cannot hide a cycle back to its active producer`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val token = (store.issueCheckpoint(request(), checkpoint()) as QueryCheckpointIssuance.Issued).token
        val claim = (store.acquireCheckpoint(token, lease, 1000) as QueryCheckpointAcquisition.Acquired).claim
        val suffix = qualified(QueryCheckpointDocument.Upstream(token))
        val output =
            store.issueOutput(request(), lease, suffix, claim, QueryRetentionByteCount.parse(512).refined())
                as QueryOutputIssuance.Issued
        val prefix =
            qualified(QueryCheckpointDocument.RetainedOutput(output.token, QueryPreparedCoverageDocument.Resumable))
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.NON_ADVANCING_SUCCESSOR),
            store.commitPublication(claim, prefix),
        )
        store.releasePublication(claim)
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(output.token, lease, 1000))
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restoreCheckpoint(token, lease))
    }

    @Test
    fun `publication checks the authority of transitive output dependencies`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val foreign = SemanticReadLease(lease.workspaceRoot, EvidenceGeneration.parse(8).refined())
        val result =
            store.issueResult(request(), QueryRetainedResult.capture(foreign, complete()).refined())
                as QueryResultIssuance.Issued
        val claim = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(
                request(),
                lease,
                page(result.reference),
                claim,
                QueryRetentionByteCount.parse(512).refined(),
            ) as QueryOutputIssuance.Issued
        val prefix =
            qualified(QueryCheckpointDocument.RetainedOutput(output.token, QueryPreparedCoverageDocument.Complete))
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.DEPENDENCY_UNAVAILABLE),
            store.commitPublication(claim, prefix),
        )
        store.releasePublication(claim)
        assertEquals(QueryOutputAcquisition.Unavailable, store.acquireOutput(output.token, lease, 1000))
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(result.reference, foreign))
    }

    @Test
    fun `publication releases staged work that its final fitted page cannot advertise`() = runTest {
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L)
        val claim = (store.acquireInitial(lease) as QueryInitialAcquisition.Acquired).claim
        val staged =
            store.issueCheckpoint(request(), checkpoint(), publicationOwner = claim) as QueryCheckpointIssuance.Issued
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(claim, page()))
        store.releasePublication(claim)
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(staged.token, lease))
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
            .executePage(request(), lease, budget)
        return retained
    }

    private suspend fun page(reference: QueryResultReference? = null): QueryPublishedPage {
        val page =
            CanonicalQueryProtocol(QueryOperations { complete() }, CanonicalQueryReferences())
                .executePage(request(), lease, budget) as OperationOutcome.Complete
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
