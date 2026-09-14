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
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryResultSet
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
    fun `checkpoint replay retains token and expiry without consuming capacity`() = runTest {
        var now = 0L
        val store = QueryCheckpointStore(capacity = 1, maximumBytes = 16384L, clock = { now })
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
                        QueryResult(QueryResultSet.Symbols(emptyList()), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(0).refined()),
                    )
                },
                CanonicalQueryReferences(),
            )
            .execute(request(), lease, budget)
        val first = store.issue(request(), retained) as QueryCheckpointIssuance.Issued
        now = 300_000_000_000L
        val second =
            store.issue(request().copy(executionBudget = largerGrant), retained) as QueryCheckpointIssuance.Issued
        assertEquals(first, second)
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restore(first.token, request(), lease))
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, store.restore(second.token, request(), lease))
        for (age in listOf(599_999_999_999L, 600_000_000_000L)) {
            now = age
            assertEquals(first, store.issue(request(), retained))
            assertInstanceOf(
                QueryCheckpointRestoration.Restored::class.java,
                store.restore(first.token, request(), lease),
            )
        }
        now = 600_000_000_001L
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restore(second.token, request(), lease))
        assertEquals(
            QueryCheckpointIssuance.CapacityExceeded,
            QueryCheckpointStore(maximumBytes = 1L).issue(request(), retained),
        )
        val renewed = store.issue(request(), retained) as QueryCheckpointIssuance.Issued
        val different =
            store.issue(request(), object : QueryCheckpoint by retained {}) as QueryCheckpointIssuance.Issued
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restore(renewed.token, request(), lease))
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            store.restore(different.token, request(), lease),
        )
    }

    @Test
    fun `checkpoint identity retains query scope projection and relationship`() = runTest {
        val store = QueryCheckpointStore()
        val original = request()
        val retained = checkpoint(lease)
        val issued = store.issue(original, retained) as QueryCheckpointIssuance.Issued
        val source = original.from as QueryFromDocument.Symbols
        val changed =
            listOf(
                original.copy(
                    from =
                        source.copy(
                            match = QueryMatchDocument.Name(text("Other"), SymbolDiscoveryMatchDocument.EXACT_NAME)
                        )
                ),
                original.copy(
                    from = source.copy(scope = source.scope.copy(sourceSets = bounded(listOf(text("main")))))
                ),
                original.copy(
                    from = source.copy(declarationKinds = bounded(listOf(QueryDeclarationKindDocument.FUNCTION)))
                ),
                original.copy(output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))),
                original.copy(steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS)))),
            )
        changed.forEach { altered ->
            assertEquals(QueryCheckpointRestoration.Mismatch, store.restore(issued.token, altered, lease))
        }
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
            assertEquals(QueryCheckpointRestoration.Mismatch, store.restore(issued.token, original, authority))
        }
        assertEquals(
            QueryCheckpointRestoration.Unavailable,
            QueryCheckpointStore().restore(issued.token, original, lease),
        )
        store.clear()
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restore(issued.token, original, lease))
    }

    @Test
    fun `checkpoint resumes each larger allowance through canonical admission with retained plan`() = runTest {
        val store = QueryCheckpointStore()
        val retained = checkpoint(lease)
        val issued = store.issue(request(), retained) as QueryCheckpointIssuance.Issued
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
                request()
                    .copy(
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
            assertEquals(issued, store.issue(requested, retained))
        }
        assertEquals(issued, store.issue(request().copy(executionBudget = ExecutionBudgetDocument()), retained))
    }

    @Test
    fun `live checkpoints bind workspace host lifetime and epoch independently`() = runTest {
        val authority = LiveReadAuthorityFixture.create(root)
        val store = QueryCheckpointStore()
        val issued = store.issue(request(), checkpoint(authority)) as QueryCheckpointIssuance.Issued
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
            assertEquals(QueryCheckpointRestoration.Mismatch, store.restore(issued.token, request(), altered))
        }
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            store.restore(issued.token, request(), authority),
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
            QueryResult(QueryResultSet.Symbols(emptyList()), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(0).refined()),
        )

    private fun request() =
        QueryRunRequest(
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
