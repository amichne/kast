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
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryCheckpointPublicationTest {
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
        grants.forEach { grant -> assertGrantReplay(retained, grant) }

        assertEquals(
            issued,
            store.issueCheckpoint(request().copy(executionBudget = ExecutionBudgetDocument()), retained),
        )
    }

    @Test
    fun `published execution page is replayed without semantic work`() = runTest {
        val store = QueryStateStore()
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
        val resume = QueryRunRequest.Resume(issued.token)
        val first = protocol.executePage(resume, lease, budget)
        val replay = protocol.executePage(resume, lease, budget)
        assertEquals(first, replay)
        assertEquals(1, calls)
    }

    @Test
    fun `concurrent checkpoint execution has one publisher`() = runTest {
        val store = QueryStateStore()
        val issued = store.issueCheckpoint(request(), checkpoint(lease)) as QueryCheckpointIssuance.Issued
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    entered.complete(Unit)
                    release.await()
                    complete()
                },
                CanonicalQueryReferences(),
                store,
            )
        val resume = QueryRunRequest.Resume(issued.token)
        val first = async { protocol.executePage(resume, lease, budget) }
        entered.await()
        val concurrent = async { protocol.executePage(resume, lease, budget) }
        testScheduler.runCurrent()
        release.complete(Unit)
        val published = first.await()
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryRunRejection.ExecutionRejected(
                io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_IN_USE
            ),
            (concurrent.await() as OperationOutcome.Rejected).reason,
        )
        assertEquals(published, protocol.executePage(resume, lease, budget))
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled checkpoint attempt releases ownership without publishing`() = runTest {
        val store = QueryStateStore()
        val issued = store.issueCheckpoint(request(), checkpoint(lease)) as QueryCheckpointIssuance.Issued
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    if (calls == 1) {
                        entered.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                    complete()
                },
                CanonicalQueryReferences(),
                store,
            )
        val resume = QueryRunRequest.Resume(issued.token)
        val attempt = launch { protocol.executePage(resume, lease, budget) }
        entered.await()
        attempt.cancel()
        attempt.join()
        assertInstanceOf(OperationOutcome.Complete::class.java, protocol.executePage(resume, lease, budget))
        assertEquals(2, calls)
    }

    @Test
    fun `initial retained rows are invisible until publication and revoked on rejection`() = runTest {
        val store = QueryStateStore()
        lateinit var prepared: QueryExecutionClaim
        lateinit var preparedPage: QueryPublishedPage
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    complete().observedWork(io.github.amichne.kast.query.contract.QueryWorkCount.parse(1).refined())
                },
                CanonicalQueryReferences(),
                store,
                QueryExecutionPublication { _, claim, page ->
                    prepared = claim
                    preparedPage = page
                    QueryExecutionPublicationResult.PREPARED
                },
            )
        val page =
            protocol.execute(
                request().copy(retention = io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument.RETAIN),
                lease,
                budget,
                retainedQueryTestPolicy(budget),
            ) as OperationOutcome.Complete
        val reference =
            (page.evidence.payload.retention as io.github.amichne.kast.protocol.contract.QueryResultRetention.Retained)
                .reference
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(reference, lease))
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(prepared, preparedPage))
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(reference, lease))

        val rejected =
            protocol.execute(
                request().copy(retention = io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument.RETAIN),
                lease,
                budget,
                retainedQueryTestPolicy(budget),
            ) as OperationOutcome.Complete
        val rejectedReference =
            (rejected.evidence.payload.retention
                    as io.github.amichne.kast.protocol.contract.QueryResultRetention.Retained)
                .reference
        store.releasePublication(prepared)
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(rejectedReference, lease))
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.CLAIM_UNAVAILABLE),
            store.commitPublication(prepared, preparedPage),
        )
        assertInstanceOf(QueryResultRestoration.Restored::class.java, store.restoreResult(reference, lease))
    }

    private suspend fun assertGrantReplay(retained: QueryCheckpoint, grant: QueryBudget) {
        val grantStore = QueryStateStore()
        val grantIssued = grantStore.issueCheckpoint(request(), retained) as QueryCheckpointIssuance.Issued
        val requested =
            QueryRunRequest.Resume(
                continuation = grantIssued.token,
                executionBudget =
                    ExecutionBudgetDocument(
                        maxElapsedMillis = grant.resources.elapsedTimeLimit,
                        maxWorkUnits = grant.resources.workUnitLimit,
                        maxResults = grant.resources.resultLimit,
                        maxReturnedBytes = ReturnedByteLimit.parse(grant.returnedBytes.value).refined(),
                    ),
            )
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    executions += 1
                    assertEquals(grant, admitted.budget)
                    assertEquals(retained, admitted.checkpoint)
                    assertEquals(retained.plan, admitted.plan)
                    complete()
                },
                CanonicalQueryReferences(),
                grantStore,
            )
        val page = protocol.executePage(requested, lease, grant)
        assertInstanceOf(OperationOutcome.Complete::class.java, page)
        assertEquals(page, protocol.executePage(requested, lease, grant))
        assertEquals(1, executions)
        assertEquals(QueryCheckpointRestoration.Unavailable, grantStore.restoreCheckpoint(grantIssued.token, lease))
        assertEquals(QueryCheckpointIssuance.Unavailable, grantStore.retainedCheckpoint(request(), retained))
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
            .executePage(request(), authority, budget)
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
