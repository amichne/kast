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
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalQueryRetentionProtocolTest {
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
    fun `retained empty result is readable without another semantic execution`() = runTest {
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    QueryExecutionResult.Complete(
                        QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(0).refined()),
                    )
                },
                CanonicalQueryReferences(),
            )
        val first =
            protocol.execute(request().copy(retention = QueryRetentionModeDocument.RETAIN), lease, budget)
                as OperationOutcome.Complete
        val reference = (first.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                lease,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(1, executions)
        assertEquals(first.evidence.payload.items, read.evidence.payload.items)
        assertEquals(first.evidence.payload.failures, read.evidence.payload.failures)
        assertEquals(QueryResultRetention.Retained(reference), read.evidence.payload.retention)
        assertNull(read.evidence.payload.nextCursor)
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_STALE_BASIS),
            (protocol.execute(
                    QueryRunRequest.ReadResult.symbols(
                        reference,
                        output = QueryOutputDocument.Symbols(bounded(emptyList())),
                    ),
                    SemanticReadLease(root, EvidenceGeneration.parse(8).refined()),
                    budget,
                ) as OperationOutcome.Rejected)
                .reason,
        )
        assertEquals(1, executions)
    }

    @Test
    fun `opaque checkpoint binds query and snapshot while allowing fresh page budget`() = runTest {
        val store = QueryStateStore()
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    executions++
                    if (admitted.checkpoint == null) {
                        val checkpoint =
                            object : QueryCheckpoint {
                                override val plan = admitted.plan
                                override val lease = admitted.lease
                                override val retainedBytes = 1024L
                            }
                        QueryExecutionResult.Qualified(
                            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                            QueryCoverage.Qualified.create(
                                    QueryCount.parse(0).refined(),
                                    setOf(QueryLimitation.WORK_LIMIT_REACHED),
                                )
                                .refined(),
                            QueryContinuationState.Resumable(checkpoint),
                        )
                    } else
                        QueryExecutionResult.Complete(
                            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                            QueryCoverage.Complete(QueryCount.parse(0).refined()),
                        )
                },
                CanonicalQueryReferences(),
                store,
            )
        val first = protocol.execute(request(), lease, budget) as OperationOutcome.Qualified
        val token = first.qualification.progress.continuationToken!!
        assertTrue(token.value.length < 64)
        assertEquals(
            ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            (first.qualification.progress as QueryQualifiedProgressDocument.Resumable).nextAction,
        )
        assertCheckpointBinding(protocol, token)
        assertInstanceOf(
            OperationOutcome.Complete::class.java,
            protocol.execute(
                QueryRunRequest.Resume(token, executionBudget = largerGrant),
                lease,
                budget.copy(returnedBytes = QueryByteLimit.parse(20000).refined()),
            ),
        )
        assertEquals(2, executions)
    }

    private suspend fun assertCheckpointBinding(protocol: CanonicalQueryProtocol, token: QueryExecutionContinuation) {
        // Resume is token-only: a caller cannot provide a conflicting replacement plan.
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH),
            (protocol.execute(
                    QueryRunRequest.Resume(token),
                    SemanticReadLease(root, EvidenceGeneration.parse(8).refined()),
                    budget,
                ) as OperationOutcome.Rejected)
                .reason,
        )
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
            (protocol.execute(
                    QueryRunRequest.Resume(
                        QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000002")
                            .refined()
                    ),
                    lease,
                    budget,
                ) as OperationOutcome.Rejected)
                .reason,
        )
    }

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
