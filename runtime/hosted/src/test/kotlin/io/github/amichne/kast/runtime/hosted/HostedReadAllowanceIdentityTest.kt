package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadAllowanceIdentityTest {
    @Test
    fun `source output owner excludes all allowance axes and page limits from retained identity`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val authority = fixture.owner.authority
        val owner = HostedQueryContinuations.Active(authority, ReadLimits.Default)
        allowanceGrowth().forEach { (low, high) ->
            val first =
                owner.sourceState.retainAcceptedFixtureSuffix(
                    fixture.request.copy(executionBudget = low),
                    authority,
                    fixture.outcome,
                ) as HostedOutputRetention.Retained
            val resumed =
                fixture.request.copy(
                    executionBudget = high,
                    entityLimit = SourceEntityLimitDocument.parse(100).value(),
                    textByteLimit = SourceTextByteLimitDocument.parse(100_000).value(),
                )
            assertEquals(fixture.outcome, owner.sourceState.readFixtureSuffix(first.token, resumed, authority))
            assertEquals(first, owner.sourceState.retainAcceptedFixtureSuffix(resumed, authority, fixture.outcome))
        }
    }
}

internal fun queryIdentityRequest(exact: ProtocolText) =
    QueryRunRequest.Run(
        QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(exact)))),
        bounded(emptyList()),
        QueryOutputDocument.Symbols(bounded(emptyList())),
        QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
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
