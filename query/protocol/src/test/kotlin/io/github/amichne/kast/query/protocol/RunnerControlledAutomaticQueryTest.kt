package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class RunnerControlledAutomaticQueryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `runner keeps its private successor across an interpage retention TTL`() = runTest {
        var now = 0L
        val script = Script(List(2) { listOf(row) })
        val store = QueryStateStore(clock = { now }, ttlMillis = 1)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val runner =
            request.copy(
                executionMode = io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
            )
        val grant = budget.copy(resources = io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled)
        val base = policy(rows = 1, nanoTime = { now })
        val observed =
            QueryInvocationPolicy(
                base.previewRows,
                base.previewBytesLimit,
                base.retainedBytes,
                base.previewBytes,
                nanoTime = base.nanoTime,
                retentionObservation =
                    object : QueryInvocationRetentionObservation {
                        override fun observe(admission: QueryInvocationRetentionAdmission) {
                            assertEquals(QueryInvocationRetentionCapacity.Unlimited, admission.capacity)
                        }

                        override fun observeFacts(
                            stage: QueryInvocationRetentionStage,
                            estimate: QueryInvocationFactsRetentionEstimate,
                            inventoryOwners: io.github.amichne.kast.relation.contract.RelationProviderRetentionLedger,
                        ) {
                            if (stage == QueryInvocationRetentionStage.FACTS_ACCEPTED) now += 10_000_000_000L
                        }
                    },
            )
        assertInstanceOf(
            OperationOutcome.Complete::class.java,
            protocol.execute(runner, fixture.authority, grant, observed),
        )
        script.assertDrained()
    }

    @Test
    fun `runner cancellation after the final page rejects completed publication`() = runTest {
        val script = Script(listOf(listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val runner =
            request.copy(
                executionMode = io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
            )
        val grant = budget.copy(resources = io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled)
        val rejection =
            completionRejection(
                protocol.execute(
                    runner,
                    fixture.authority,
                    grant,
                    policy(cancelled = { script.calls == 1 }),
                )
            )
        assertEquals(QueryInvocationStop.CANCELLED, rejection.stop)
        script.assertDrained()
    }

    @Test
    fun `runner semantic pages keep acquired lifetime beyond the retention TTL`() = runTest {
        var now = 0L
        val script = Script(List(2) { listOf(row, row) }, afterPage = { now += 10_000_000_000L })
        val store = QueryStateStore(clock = { now }, ttlMillis = 1)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val runner =
            request.copy(
                executionMode = io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
            )
        val grant = budget.copy(resources = io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled)
        val completed =
            assertInstanceOf(
                OperationOutcome.Complete::class.java,
                protocol.execute(runner, fixture.authority, grant, policy(rows = 1, nanoTime = { now })),
            )
        val payload =
            assertInstanceOf(
                io.github.amichne.kast.protocol.contract.QueryRunResult::class.java,
                completed.evidence.payload,
            )
        val retained =
            assertInstanceOf(
                io.github.amichne.kast.protocol.contract.QueryResultRetention.Retained::class.java,
                payload.retention,
            )
        script.assertDrained()
        now += 10_000_000L
        assertInstanceOf(
            OperationOutcome.Rejected::class.java,
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(retained.reference, payload.nextCursor!!, output),
                fixture.authority,
                grant,
            ),
        )
        assertEquals(2, script.calls)
    }

    @Test
    fun `runner completes beyond elapsed work and internal storage quotas with bounded presentation`() = runTest {
        var now = 0L
        val script = Script(List(3) { listOf(row) }, afterPage = { now += 10_000_000_000L })
        val store = QueryStateStore(capacity = 1, maximumBytes = 1)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val runner =
            request.copy(
                executionMode = io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
            )
        val grant =
            budget.copy(
                resources = io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled,
                checkpointBytes = io.github.amichne.kast.query.contract.QueryByteLimit.parse(1).refined(),
                pageResults = ResultLimit.parse(1).refined(),
            )
        val completed =
            protocol.execute(
                runner,
                fixture.authority,
                grant,
                policy(rows = 1, retainedBytes = 1, nanoTime = { now }),
            )
        val result = assertInstanceOf(OperationOutcome.Complete::class.java, completed)
        assertEquals(
            1,
            assertInstanceOf(
                    io.github.amichne.kast.protocol.contract.QueryRunResult::class.java,
                    result.evidence.payload,
                )
                .items
                .values
                .size,
        )
        assertEquals(
            3,
            assertInstanceOf(
                    io.github.amichne.kast.protocol.contract.QueryRunResult::class.java,
                    result.evidence.payload,
                )
                .invocation!!
                .accumulatedRowCount,
        )
        assertEquals(List(3) { io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled }, script.resourceGrants)
        org.junit.jupiter.api.Assertions.assertTrue(store.retentionMeasurements().retainedBytes.value > 1L)
        script.assertDrained()
    }

    @Test
    fun `runner cancellation rejects completion before another semantic page`() = runTest {
        val script = Script(List(2) { listOf(row) })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val runner =
            request.copy(
                executionMode = io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
            )
        val grant = budget.copy(resources = io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled)
        val rejection =
            completionRejection(
                protocol.execute(
                    runner,
                    fixture.authority,
                    grant,
                    policy(cancelled = { script.calls == 1 }),
                )
            )
        assertEquals(QueryInvocationStop.CANCELLED, rejection.stop)
        assertEquals(1, script.calls)
    }
}
