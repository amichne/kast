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
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryConcatAdmissionTest {
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
    fun `unissued concatenated reference reports its step and token position before execution`() = runTest {
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Rejected references must not execute") },
                CanonicalQueryReferences(),
            )
        val input = request().copy(steps = bounded(listOf(concat(text("NON_ISSUED")))))
        val rejection = (protocol.execute(input, lease, budget) as OperationOutcome.Rejected).reason
        val step = assertInstanceOf(QueryRunRejection.StepReferenceRejected::class.java, rejection)
        assertEquals(0, step.stepPosition.value)
        assertEquals(0, step.referencePosition.value)
    }

    @Test
    fun `fresh discovery query reacquires concatenated exact references before plan admission`() = runTest {
        val fixture = RelationPagingFixture.live()
        val strict =
            object : QueryReferenceAuthority by fixture.references {
                override fun restoreExact(
                    token: ProtocolText,
                    current: SemanticReadAuthority,
                ): CanonicalSelectorDecoding<SymbolSelector> =
                    CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE)
            }
        var acquisitions = 0
        var executions = 0
        val references =
            ReacquiringQueryReferences(
                strict,
                ExactReferenceReacquisition { _, _ ->
                    acquisitions++
                    CanonicalSelectorDecoding.Decoded(fixture.selector)
                },
            )
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    executions++
                    val stage = (admitted.plan as AdmittedQueryPlan.Symbols).stage
                    assertEquals(
                        fixture.selector,
                        ((stage as ExactQueryStage.Concat).input
                                as io.github.amichne.kast.query.contract.QueryCompositionInput.ExactReferences)
                            .references
                            .values
                            .single(),
                    )
                    qualifiedThenComplete(admitted, executions)
                },
                references,
            )
        val input = request().copy(steps = bounded(listOf(concat(fixture.exact))))
        val first = protocol.execute(input, fixture.authority, budget) as OperationOutcome.Qualified
        val continuation = first.qualification.progress.continuationToken!!
        assertInstanceOf(
            OperationOutcome.Complete::class.java,
            protocol.execute(QueryRunRequest.Resume(continuation), fixture.authority, budget),
        )
        assertEquals(1, acquisitions)
        assertEquals(2, executions)
    }

    private fun qualifiedThenComplete(admitted: QueryExecutionRequest, execution: Int): QueryExecutionResult {
        val result = QueryResult(QueryRows.Symbols.of(emptyList()), emptyList())
        if (execution != 1)
            return QueryExecutionResult.Complete(
                result,
                QueryCoverage.Complete(QueryCount.parse(0).refined()),
            )
        val checkpoint =
            object : QueryCheckpoint {
                override val plan = admitted.plan
                override val lease = admitted.lease
                override val retainedBytes = 1024L
            }
        return QueryExecutionResult.Qualified(
            result,
            QueryCoverage.Qualified.create(
                    QueryCount.parse(0).refined(),
                    setOf(QueryLimitation.WORK_LIMIT_REACHED),
                )
                .refined(),
            QueryContinuationState.Resumable(checkpoint),
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

    private fun concat(reference: ProtocolText) =
        QueryStepDocument.Concat(
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(reference))))
        )

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
