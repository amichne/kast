@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ExecutionBudgetClamp
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Admission evidence for #772; native operation and installed transport qualification remain separate. */
class HostedRepairBudgetMatrixTest {
    @Test
    fun `ten and twenty second requests retain actual grants under short and default policies`() = runTest {
        for (policy in Policy.entries) for (requestedMillis in listOf(10_000L, 20_000L)) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val origin = testScheduler.currentTime
            val clock = { (testScheduler.currentTime - origin) * 1_000_000L }
            val executor =
                HostedQueryExecutor(backgroundScope, clock) { limits ->
                    HostedReadDiagnostics(clock, limits, receipts::add)
                }
            val expectedGrant = if (policy == Policy.SHORT) 2_750L else requestedMillis
            lateinit var report: ExecutionBudgetReport
            val request =
                HostedExecutionBudgetRequest(
                    RequestedExecutionBudget(
                        elapsed = ExecutionAllowance.Requested(ElapsedTimeLimitMillis.parse(requestedMillis).proven())
                    )
                )
            val result =
                executor.execute(executor.endpoint, policy.limits(), executionBudget = request) { progress ->
                    progress.advance(HostedQueryStage.MODEL_CAPTURE)
                    delay(1_000L)
                    runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                        checkGrant(allowance, policy, requestedMillis, expectedGrant)
                        report = ExecutionBudgetReport.from(allowance.executionBudget)
                        delay(expectedGrant)
                        Unit
                    }
                }
            assertEquals(
                HostedExecution.Completed(
                    HostedSemanticRead.Resolved(Unit),
                    HostedQueryStage.RESULT_DETACHED,
                    ExecutionBudgetPresence.Present(report),
                ),
                result,
            )
            checkReceipt(receipts.single(), policy, expectedGrant)
            executor.retire()
            executor.drain()
        }
    }

    private fun checkGrant(
        allowance: HostedSemanticTimeAllowance,
        policy: Policy,
        requestedMillis: Long,
        expectedGrant: Long,
    ) {
        val elapsed = allowance.executionBudget.elapsed
        assertEquals(requestedMillis, (elapsed.requested as ExecutionAllowance.Requested).value.value)
        assertEquals(2_000L, elapsed.configuredDefault.value)
        assertEquals((Int.MAX_VALUE - 1).toLong(), elapsed.operatorCeiling.value)
        assertEquals(expectedGrant, elapsed.effective.value)
        assertEquals(
            if (policy == Policy.SHORT) setOf(ExecutionBudgetClamp.DEADLINE_REMAINING) else emptySet(),
            elapsed.clamping,
        )
    }

    private fun checkReceipt(receipt: HostedReadDiagnosticReceipt, policy: Policy, expectedGrant: Long) {
        assertEquals((1_000L + expectedGrant) * 1_000_000L, receipt.durationNanos)
        assertEquals(
            1_000_000_000L,
            receipt.stages.single { it.stage == HostedQueryStage.MODEL_CAPTURE }.durationNanos,
        )
        assertEquals(
            expectedGrant * 1_000_000L,
            receipt.stages.single { it.stage == HostedQueryStage.SEMANTIC_READ }.durationNanos,
        )
        assertEquals(
            HostedSemanticBudgetObservation.Admitted(
                if (policy == Policy.SHORT) 3_000L else 29_000L,
                250L,
                expectedGrant,
                2_000L,
            ),
            receipt.semanticBudget,
        )
    }

    private enum class Policy {
        SHORT,
        DEFAULT;

        fun limits(): ReadLimits =
            when (this) {
                SHORT -> shortHostLimits()
                DEFAULT -> ReadLimits.Default
            }
    }

    companion object {
        private fun <Value> Refinement<Value, *>.proven(): Value =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error("Fixture admission rejected: $failure")
            }
    }
}
