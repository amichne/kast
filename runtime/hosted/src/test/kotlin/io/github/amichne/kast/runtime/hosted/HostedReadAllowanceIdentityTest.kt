package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadAllowanceIdentityTest {
    @Test
    fun `query output owner excludes all four allowance axes from retained identity`() = runTest {
        val fixture = RelationPagingFixture.live()
        val authority = fixture.authority
        val owner = HostedQueryContinuations.Active(authority, ReadLimits.Default)
        val request = queryIdentityRequest(fixture.exact)
        val evidence = (fixture.page() as OperationOutcome.Qualified).evidence
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    evidence.basis,
                    QueryRunResult(bounded(emptyList()), bounded(emptyList())),
                )
            )
        allowanceGrowth().forEach { (low, high) ->
            val first =
                owner.issue(request.copy(executionBudget = low), authority, outcome) as HostedOutputRetention.Retained
            val resumed = request.copy(continuation = first.token, executionBudget = high)
            assertEquals(outcome, owner.restore(first.token, resumed, authority))
            assertEquals(outcome, owner.restore(first.token, resumed, authority))
            assertEquals(first, owner.issue(resumed, authority, outcome))
        }
    }

    @Test
    fun `relation output owner excludes all allowance axes and page size from retained identity`() = runTest {
        val fixture = RelationPagingFixture.live()
        val owner = HostedQueryContinuations.Active(fixture.authority, ReadLimits.Default)
        val request = fixture.request(RelationReadPositionDocument.Start)
        val outcome = fixture.page()
        allowanceGrowth().forEach { (low, high) ->
            val first =
                owner.relationOutputs.issue(request.copy(executionBudget = low), fixture.authority, outcome)
                    as HostedOutputRetention.Retained
            val resumed = request.copy(executionBudget = high, limit = ProtocolCount.parse(100).value())
            assertEquals(outcome, owner.relationOutputs.restore(first.token, resumed, fixture.authority))
            assertEquals(first, owner.relationOutputs.issue(resumed, fixture.authority, outcome))
        }
    }

    @Test
    fun `source output owner excludes all allowance axes and page limits from retained identity`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val authority = fixture.owner.authority
        val owner = HostedQueryContinuations.Active(authority, ReadLimits.Default)
        allowanceGrowth().forEach { (low, high) ->
            val first =
                owner.sourceOutputs.issue(fixture.request.copy(executionBudget = low), authority, fixture.outcome)
                    as HostedOutputRetention.Retained
            val resumed =
                fixture.request.copy(
                    executionBudget = high,
                    entityLimit = SourceEntityLimitDocument.parse(100).value(),
                    textByteLimit = SourceTextByteLimitDocument.parse(100_000).value(),
                )
            assertEquals(fixture.outcome, owner.sourceOutputs.restore(first.token, resumed, authority))
            assertEquals(first, owner.sourceOutputs.issue(resumed, authority, fixture.outcome))
        }
    }

    @Test
    fun `traversal output owner excludes allowances but preserves initially defaulted strategy`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        val authority = fixture.owner.authority
        val owner = HostedQueryContinuations.Active(authority, ReadLimits.Default)
        allowanceGrowth().forEach { (low, high) ->
            val first =
                owner.traversalOutputs.issue(fixture.request.copy(executionBudget = low), authority, fixture.outcome)
                    as HostedOutputRetention.Retained
            val resumed =
                fixture.request.copy(
                    executionBudget = high,
                    maximumResults = ProtocolCount.parse(100).value(),
                    position = TraversalRunPositionDocument.Start,
                    strategy = TraversalStrategyDocument.BreadthFirst,
                )
            assertEquals(fixture.outcome, owner.traversalOutputs.restore(first.token, resumed, authority))
            assertEquals(first, owner.traversalOutputs.issue(resumed, authority, fixture.outcome))
            assertEquals(
                OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_REQUEST_MISMATCH),
                owner.traversalOutputs.restore(
                    first.token,
                    resumed.copy(strategy = TraversalStrategyDocument.BoundedFanOut(ProtocolCount.parse(1).value())),
                    authority,
                ),
            )
        }
    }
}

internal fun queryIdentityRequest(exact: ProtocolText) =
    QueryRunRequest(
        QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(exact)))),
        bounded(emptyList()),
        QueryOutputDocument.Symbols(bounded(emptyList())),
        QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
    )

private fun allowanceGrowth() =
    listOf(
        ExecutionBudgetDocument(maxElapsedMillis = ElapsedTimeLimitMillis.parse(1).value()) to
            ExecutionBudgetDocument(maxElapsedMillis = ElapsedTimeLimitMillis.parse(1000).value()),
        ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(1).value()) to
            ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(1000).value()),
        ExecutionBudgetDocument(maxResults = ResultLimit.parse(1).value()) to
            ExecutionBudgetDocument(maxResults = ResultLimit.parse(100).value()),
        ExecutionBudgetDocument(maxReturnedBytes = ReturnedByteLimit.parse(1).value()) to
            ExecutionBudgetDocument(maxReturnedBytes = ReturnedByteLimit.parse(100_000).value()),
    )

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
