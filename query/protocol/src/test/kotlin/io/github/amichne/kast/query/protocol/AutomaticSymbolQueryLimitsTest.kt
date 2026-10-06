package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryLimitsTest : AutomaticSymbolQueryCase() {
    @Test
    fun `aggregate work stop preserves a valid continuation and proven rows`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val limited = budget.copy(resources = budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(8).refined()))
        val result =
            protocol.executeAutomatically(request, fixture.authority, limited, policy()) as OperationOutcome.Qualified
        assertEquals(1, script.calls)
        assertEquals(1, result.evidence.payload.items.values.size)
        assertEquals(QueryInvocationStop.WORK_LIMIT, result.evidence.payload.invocation!!.stop)
        val resumed =
            protocol.execute(
                QueryRunRequest.Resume(result.qualification.progress.continuationToken!!),
                fixture.authority,
                budget,
            )
        assertInstanceOf(OperationOutcome.Complete::class.java, resumed)
        script.assertDrained()
    }

    @Test
    fun `cooperative cancellation returns accumulated rows and a still valid continuation`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(
                request,
                fixture.authority,
                budget,
                policy(cancelled = { script.calls == 1 }),
            ) as OperationOutcome.Qualified
        assertEquals(1, script.calls)
        assertEquals(1, result.evidence.payload.items.values.size)
        assertEquals(QueryInvocationStop.CANCELLED, result.evidence.payload.invocation!!.stop)
        assertNotNull(result.qualification.progress.continuationToken)
        protocol.execute(
            QueryRunRequest.Resume(result.qualification.progress.continuationToken!!),
            fixture.authority,
            budget,
        )
        script.assertDrained()
    }

    @Test
    fun `elapsed allowance is invocation wide and cannot be renewed by a resume`() = runTest {
        var now = 0L
        val script = Script(listOf(listOf(row), listOf(row)), afterPage = { now = 1_000_000_000L })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy(nanoTime = { now }))
                as OperationOutcome.Qualified
        assertEquals(1, script.calls)
        assertEquals(QueryInvocationStop.TIME_LIMIT, result.evidence.payload.invocation!!.stop)
        assertNotNull(result.qualification.progress.continuationToken)
        protocol.execute(
            QueryRunRequest.Resume(result.qualification.progress.continuationToken!!),
            fixture.authority,
            budget,
        )
        script.assertDrained()
    }

    @Test
    fun `remaining elapsed grant shrinks even after a resumable page limit`() = runTest {
        var now = 0L
        val script = Script(listOf(listOf(row), listOf(row)), afterPage = { now = 900_000_000L })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.executeAutomatically(request, fixture.authority, budget, policy(nanoTime = { now }))
        assertInstanceOf(OperationOutcome.Complete::class.java, result)
        assertEquals(listOf(250L, 100L), script.timeGrants)
        script.assertDrained()
    }

    @Test
    fun `aggregate retained bytes stop without skipping a consumed page through a continuation`() = runTest {
        val script = Script(listOf(listOf(row), List(100) { row }))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(
                request,
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
                policy(retainedBytes = 50_000),
            ) as OperationOutcome.Qualified
        assertEquals(QueryInvocationStop.RETAINED_BYTES_LIMIT, result.evidence.payload.invocation!!.stop)
        assertEquals(1, result.evidence.payload.invocation!!.accumulatedRowCount)
        assertNull(result.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `hard cancellation never blindly replays semantic work`() = runTest {
        var calls = 0
        val operations = QueryOperations {
            calls++
            throw kotlinx.coroutines.CancellationException("scripted hard timeout")
        }
        val protocol = CanonicalQueryProtocol(operations, fixture.references)
        try {
            protocol.executeAutomatically(request, fixture.authority, budget, policy())
            fail<Unit>("Expected hard cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `budget increase next action requires a decision and preserves the issued checkpoint`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val store = QueryStateStore()
        var receipt: QueryExecutionResult? = null
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { script.operations.run(it).also { result -> receipt = result } },
                fixture.references,
                store,
            )
        var calls = 0
        val accumulated =
            AutomaticSymbolQueryRunner(store, fixture.authority, policy()) { action, remaining ->
                    calls++
                    val original = protocol.execute(action, fixture.authority, remaining) as OperationOutcome.Qualified
                    val progress = original.qualification.progress as QueryQualifiedProgressDocument.Resumable
                    QueryInvocationPage(
                        OperationOutcome.Qualified(
                            original.evidence,
                            QueryRunQualification.create(
                                    original.qualification.knownMinimum,
                                    original.qualification.limitations,
                                    progress.copy(nextAction = ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET),
                                )
                                .refined(),
                        ),
                        receipt,
                    )
                }
                .run(request, budget)
        assertEquals(1, calls)
        assertEquals(QueryInvocationStop.BUDGET_INCREASE_REQUIRED, accumulated.stop)
        val presented = present(store, accumulated) as OperationOutcome.Qualified
        assertEquals(
            ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            (presented.qualification.progress as QueryQualifiedProgressDocument.Resumable).nextAction,
        )

        assertInstanceOf(
            QueryContinuationState.Resumable::class.java,
            (accumulated.execution as QueryExecutionResult.Qualified).continuation,
        )
        val next =
            (accumulated.execution as QueryExecutionResult.Qualified).continuation as QueryContinuationState.Resumable
        val issued = store.retainedCheckpoint(request, next.checkpoint) as QueryCheckpointIssuance.Issued
        protocol.execute(QueryRunRequest.Resume(issued.token), fixture.authority, budget)
        script.assertDrained()
    }

    private suspend fun present(store: QueryStateStore, accumulated: AccumulatedSymbolQuery): QueryPublishedPage {
        val claim = (store.acquireInitial(fixture.authority) as QueryInitialAcquisition.Acquired).claim
        return QueryPagePublication(store, QueryExecutionPublication.Immediate).execute(claim) {
            QueryInvocationProjection(fixture.references, store, QueryResultRetentionObservation.None)
                .project(request, fixture.authority, accumulated, policy(), claim)
        }
    }
}
