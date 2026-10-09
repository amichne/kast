package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointPartialSymbols
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CompleteOnlyCheckpointRecoveryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `strict work rejection exposes checkpoint proof without execution count inflation or replay`() = runTest {
        val script = BlockingScript()
        val strict = request
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejection = result.reason as QueryRunRejection.CompletionUnproven
        assertEquals(QueryInvocationStop.WORK_LIMIT, rejection.stop)
        assertEquals(0, (rejection.originalCoverage as QueryCompletionCoverageDocument.Qualified).knownMinimum.value)
        val retained = rejection.evidence as QueryCompletionEvidenceDocument.Retained
        assertEquals(1, retained.preview.values.size, "Proven terminal group must be recoverable")
        val read = protocol.executePage(retained.readRequest(), fixture.authority, budget) as OperationOutcome.Qualified
        assertEquals(1, read.evidence.payload.items.values.size)
        assertTrue(
            read.qualification.limitations.contains(
                io.github.amichne.kast.protocol.contract.QueryLimitationDocument.WORK_LIMIT_REACHED
            )
        )
        script.assertDrained()
    }

    @Test
    fun `internal work page keeps hidden grouping facts out of emitted rows`() = runTest {
        val script = BlockingScript()
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .executePage(request, fixture.authority, budget) as OperationOutcome.Qualified
        assertTrue(result.evidence.payload.items.values.isEmpty())
        assertEquals(0, result.qualification.knownMinimum.value)
        script.assertDrained()
    }

    private inner class BlockingScript {
        private var calls = 0
        val operations = QueryOperations { execution ->
            calls++
            assertEquals(1, calls, "Recovery must never replay semantic work")
            QueryExecutionResult.Qualified(
                    QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                    QueryCoverage.Qualified.create(
                            QueryCount.parse(0).refined(),
                            setOf(QueryLimitation.WORK_LIMIT_REACHED),
                        )
                        .refined(),
                    QueryContinuationState.Resumable(Partial(execution.plan, execution.lease)),
                )
                .observedWork(QueryWorkCount.parse(execution.budget.resources.workUnitLimit.value).refined())
        }

        fun assertDrained() = assertEquals(1, calls)
    }

    private inner class Partial(
        override val plan: AdmittedQueryPlan,
        override val lease: SemanticReadAuthority,
    ) : QueryCheckpointPartialSymbols {
        override val retainedBytes = 100L

        override fun partialSymbols(rowLimit: ResultLimit, byteLimit: QueryByteLimit): QueryRows.Symbols {
            assertTrue(rowLimit.value >= 1)
            assertTrue(byteLimit.value >= 1)
            return QueryRows.Symbols.of(listOf(row))
        }
    }
}
