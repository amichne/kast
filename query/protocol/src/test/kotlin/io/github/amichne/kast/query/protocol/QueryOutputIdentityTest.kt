package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
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
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryOutputIdentityTest {
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

    @Test
    fun `detached output identity excludes caller grants on all four axes`() = runTest {
        val store = QueryStateStore()
        lateinit var claim: QueryExecutionClaim
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { complete() },
                CanonicalQueryReferences(),
                store,
                QueryExecutionPublication { _, active, _ ->
                    claim = active
                    QueryExecutionPublicationResult.PREPARED
                },
            )
        val requests = grants()
        requests.forEach { (low, high) ->
            val firstRequest = request().copy(executionBudget = low)
            val page = protocol.executePage(firstRequest, lease, budget)
            val first =
                store.issueOutput(firstRequest, lease, page, claim, QueryRetentionByteCount.parse(512).refined())
                    as QueryOutputIssuance.Issued
            assertEquals(
                QueryPublicationCommit.Committed,
                store.commitPublication(claim, page.advertisingOutput(first.token)),
            )
            val secondRequest = request().copy(executionBudget = high)
            val secondPage = protocol.executePage(secondRequest, lease, budget)
            val second =
                store.issueOutput(secondRequest, lease, secondPage, claim, QueryRetentionByteCount.parse(512).refined())
                    as QueryOutputIssuance.Issued
            assertEquals(first.token, second.token)
            store.releasePublication(claim)
            val restored = store.acquireOutput(first.token, lease, 1000) as QueryOutputAcquisition.Acquired
            assertEquals(page, restored.page)
            store.releasePublication(restored.claim)
        }
    }

    private fun grants() =
        listOf(
            io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                maxElapsedMillis = ElapsedTimeLimitMillis.parse(1).refined()
            ) to
                io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                    maxElapsedMillis = ElapsedTimeLimitMillis.parse(1000).refined()
                ),
            io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                maxWorkUnits = WorkUnitLimit.parse(1).refined()
            ) to
                io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                    maxWorkUnits = WorkUnitLimit.parse(1000).refined()
                ),
            io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                maxResults = ResultLimit.parse(1).refined()
            ) to
                io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                    maxResults = ResultLimit.parse(100).refined()
                ),
            io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                maxReturnedBytes = ReturnedByteLimit.parse(1).refined()
            ) to
                io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                    maxReturnedBytes = ReturnedByteLimit.parse(100_000).refined()
                ),
        )

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
