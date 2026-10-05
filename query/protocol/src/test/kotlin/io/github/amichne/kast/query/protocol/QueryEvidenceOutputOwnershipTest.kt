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
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.presentationSuffix
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

class QueryEvidenceOutputOwnershipTest {
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
    fun `evidence only successors reduce immutable output without another semantic execution`() = runTest {
        val failures =
            List(3) { index ->
                QueryItemFailureDocument.ExactReference(
                    QueryReferenceDocument.ExactSymbol(text("exact:v3:resource$index")),
                    QueryExactFailureDocument.AMBIGUOUS_DECLARATION,
                )
            }
        val fixture = EvidenceOutputCase(failures)
        val first = fixture.issueInitial()
        val second = fixture.advance(first)
        val third = fixture.advance(second)
        fixture.finish(third)
        assertEquals(failures, fixture.emitted)
        assertEquals(1, fixture.executions)
    }

    private inner class EvidenceOutputCase(private val failures: List<QueryItemFailureDocument>) {
        val emitted = mutableListOf<QueryItemFailureDocument>()
        var executions = 0
            private set

        private val store = QueryStateStore()
        private lateinit var claim: QueryExecutionClaim
        private val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    assertEquals(1, executions)
                    complete()
                },
                CanonicalQueryReferences(),
                store,
                QueryExecutionPublication { _, active, _ ->
                    claim = active
                    QueryExecutionPublicationResult.PREPARED
                },
            )

        suspend fun issueInitial(): QueryExecutionContinuation.Output {
            val initial = protocol.execute(request(), lease, budget) as OperationOutcome.Complete
            val page =
                initial.copy(
                    evidence =
                        initial.evidence.copy(payload = initial.evidence.payload.copy(failures = bounded(failures)))
                )
            val token = retain(request(), page)
            publish(page.advertisingOutput(token))
            return token
        }

        suspend fun advance(token: QueryExecutionContinuation.Output): QueryExecutionContinuation.Output {
            val request = QueryRunRequest.Resume(token)
            val semantic = protocol.execute(request, lease, budget) as OperationOutcome.Complete
            val page = semantic.evidence.payload
            assertEquals(0, page.items.values.size)
            val suffix =
                semantic.copy(evidence = semantic.evidence.copy(payload = page.presentationSuffix(1).refined()))
            val next = retain(request, suffix)
            val prefix =
                semantic.copy(evidence = semantic.evidence.copy(payload = page.presentationPrefix(1).refined()))
            emitted += prefix.evidence.payload.failures.values
            publish(prefix.advertisingOutput(next))
            return next
        }

        suspend fun finish(token: QueryExecutionContinuation.Output) {
            val semantic = protocol.execute(QueryRunRequest.Resume(token), lease, budget) as OperationOutcome.Complete
            assertEquals(0, semantic.evidence.payload.items.values.size)
            emitted += semantic.evidence.payload.failures.values
            publish(semantic)
        }

        private fun retain(request: QueryRunRequest, page: QueryPublishedPage): QueryExecutionContinuation.Output =
            (store.issueOutput(request, lease, page, claim, QueryRetentionByteCount.parse(512).refined())
                    as QueryOutputIssuance.Issued)
                .token

        private fun publish(page: QueryPublishedPage) {
            assertEquals(QueryPublicationCommit.Committed, store.commitPublication(claim, page))
            store.releasePublication(claim)
        }
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
}
