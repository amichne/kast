package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
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
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryCheckpointReplayTest {
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
    fun `result references share bounded expiry and reject a different semantic basis`() {
        var now = 0L
        val store = QueryStateStore(capacity = 1, maximumBytes = 16_384L, clock = { now })
        val snapshot = QueryRetainedResult.capture(lease, complete()).refined()
        val issued = store.issueResult(request(), snapshot) as QueryResultIssuance.Issued
        val restored = store.restoreResult(issued.reference, lease) as QueryResultRestoration.Restored
        assertEquals(emptyList<Any>(), restored.result.symbols)
        val different = SemanticReadLease(root, EvidenceGeneration.parse(8).refined())
        assertEquals(QueryResultRestoration.StaleBasis, store.restoreResult(issued.reference, different))
        now = 600_000_000_001L
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease))
        assertEquals(
            QueryResultIssuance.CapacityExceeded,
            QueryStateStore(maximumBytes = 1L).issueResult(request(), snapshot),
        )
    }

    @Test
    fun `checkpoint replay retains token and expiry without consuming capacity`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 1, maximumBytes = 16384L, clock = { now })
        lateinit var retained: QueryCheckpoint
        CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    retained =
                        object : QueryCheckpoint {
                            override val plan = admitted.plan
                            override val lease = admitted.lease
                            override val retainedBytes = 1024L
                        }
                    QueryExecutionResult.Complete(
                        QueryResult(emptyList(), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(0).refined()),
                    )
                },
                CanonicalQueryReferences(),
            )
            .execute(request(), lease, budget)
        val first = store.issueCheckpoint(request(), retained) as QueryCheckpointIssuance.Issued
        now = 300_000_000_000L
        val second =
            store.issueCheckpoint(request().copy(executionBudget = largerGrant), retained)
                as QueryCheckpointIssuance.Issued
        assertEquals(first, second)
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restoreCheckpoint(first.token, lease))
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restoreCheckpoint(second.token, lease))
        for (age in listOf(599_999_999_999L, 600_000_000_000L)) {
            now = age
            assertEquals(first, store.issueCheckpoint(request(), retained))
            assertInstanceOf(
                QueryCheckpointRestoration.Restored::class.java,
                store.restoreCheckpoint(first.token, lease),
            )
        }
        now = 600_000_000_001L
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(second.token, lease))
        assertEquals(
            QueryCheckpointIssuance.CapacityExceeded,
            QueryStateStore(maximumBytes = 1L).issueCheckpoint(request(), retained),
        )
        assertReplacementRetiresPrevious(store, retained)
    }

    private fun assertReplacementRetiresPrevious(store: QueryStateStore, retained: QueryCheckpoint) {
        val renewed = store.issueCheckpoint(request(), retained) as QueryCheckpointIssuance.Issued
        val different =
            store.issueCheckpoint(request(), object : QueryCheckpoint by retained {}) as QueryCheckpointIssuance.Issued
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(renewed.token, lease))
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            store.restoreCheckpoint(different.token, lease),
        )
    }

    @Test
    fun `checkpoint retains the admitted query plan and rejects another basis`() = runTest {
        val store = QueryStateStore()
        val original = request()
        val retained = checkpoint(lease)
        val issued = store.issueCheckpoint(original, retained) as QueryCheckpointIssuance.Issued
        val restored = store.restoreCheckpoint(issued.token, lease) as QueryCheckpointRestoration.Restored
        assertEquals(original, restored.request)
        assertEquals(retained, restored.checkpoint)
        val authorities =
            listOf(
                SemanticReadLease(
                    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).refined(),
                    lease.generation,
                ),
                SemanticReadLease(root, EvidenceGeneration.parse(8).refined()),
                LiveReadAuthorityFixture.create(root),
            )
        authorities.forEach { authority ->
            assertEquals(QueryCheckpointRestoration.Mismatch, store.restoreCheckpoint(issued.token, authority))
        }
        assertEquals(
            QueryCheckpointRestoration.Unavailable,
            QueryStateStore().restoreCheckpoint(issued.token, lease),
        )
        store.clear()
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(issued.token, lease))
    }

    @Test
    fun `checkpoint resumes each larger allowance through canonical admission with retained plan`() = runTest {
        val store = QueryStateStore()
        val retained = checkpoint(lease)
        val issued = store.issueCheckpoint(request(), retained) as QueryCheckpointIssuance.Issued
        val grants =
            listOf(
                budget.copy(
                    resources = budget.resources.copy(elapsedTimeLimit = ElapsedTimeLimitMillis.parse(2000).refined())
                ),
                budget.copy(resources = budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(200).refined())),
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(20).refined())),
                budget.copy(returnedBytes = QueryByteLimit.parse(20000).refined()),
            )
        grants.forEach { grant ->
            val requested =
                QueryRunRequest.Resume(
                    continuation = issued.token,
                    executionBudget =
                        ExecutionBudgetDocument(
                            maxElapsedMillis = grant.resources.elapsedTimeLimit,
                            maxWorkUnits = grant.resources.workUnitLimit,
                            maxResults = grant.resources.resultLimit,
                            maxReturnedBytes = ReturnedByteLimit.parse(grant.returnedBytes.value).refined(),
                        ),
                )
            val protocol =
                CanonicalQueryProtocol(
                    QueryOperations { admitted ->
                        assertEquals(grant, admitted.budget)
                        assertEquals(retained, admitted.checkpoint)
                        assertEquals(retained.plan, admitted.plan)
                        complete()
                    },
                    CanonicalQueryReferences(),
                    store,
                )
            assertInstanceOf(OperationOutcome.Complete::class.java, protocol.execute(requested, lease, grant))
            assertEquals(
                issued,
                store.issueCheckpoint(request().copy(executionBudget = requested.executionBudget), retained),
            )
        }
        assertEquals(
            issued,
            store.issueCheckpoint(request().copy(executionBudget = ExecutionBudgetDocument()), retained),
        )
    }

    @Test
    fun `live checkpoints bind workspace host lifetime and epoch independently`() = runTest {
        val authority = LiveReadAuthorityFixture.create(root)
        val store = QueryStateStore()
        val issued = store.issueCheckpoint(request(), checkpoint(authority)) as QueryCheckpointIssuance.Issued
        val changed =
            listOf(
                LiveReadAuthorityFixture.create(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).refined()),
                LiveReadAuthorityFixture.create(
                    root,
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                ),
                LiveReadAuthorityFixture.create(root),
            )
        changed.forEach { altered ->
            assertEquals(QueryCheckpointRestoration.Mismatch, store.restoreCheckpoint(issued.token, altered))
        }
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            store.restoreCheckpoint(issued.token, authority),
        )
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
        QueryExecutionResult.Complete(
            QueryResult(emptyList(), emptyList()),
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
