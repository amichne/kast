package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ExecutionBudgetClamp
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedExecutionBudgetTest {
    @Test
    fun `admission subtracts elapsed model time and publication reserve from larger caller allowance`() {
        var now = 0L
        val request =
            HostedExecutionBudgetRequest(
                RequestedExecutionBudget(
                    elapsed = ExecutionAllowance.Requested(ElapsedTimeLimitMillis.parse(10_000).proven()),
                    work = ExecutionAllowance.Requested(WorkUnitLimit.parse(5).proven()),
                    results = ExecutionAllowance.Requested(ResultLimit.parse(2_000).proven()),
                    returnedBytes = ExecutionAllowance.Requested(ReturnedByteLimit.parse(100_000).proven()),
                ),
                maximumResults = ResultLimit.parse(1_000).proven(),
            )
        val deadline = HostedReadDeadline(ReadLimits.Default, { now }, request)
        now = 1_000_000_000L
        val grant = deadline.admit(null).proven().executionBudget
        assertEquals(2_750L, grant.elapsed.effective.value)
        assertEquals(5L, grant.work.effective.value)
        assertEquals(1_000, grant.results.effective.value)
        assertEquals(65_536L, grant.returnedBytes.effective.value)
        assertEquals(setOf(ExecutionBudgetClamp.DEADLINE_REMAINING), grant.elapsed.clamping)
        now = 3_750_000_000L
        assertEquals(Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), deadline.admit(null))
    }

    @Test
    fun `operator ceiling differs from defaults and caps every caller dimension`() {
        val limits =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_EXECUTION_MAX_MILLIS" to "800",
                            "KAST_READ_EXECUTION_MAX_WORK" to "12",
                            "KAST_READ_EXECUTION_MAX_RESULTS" to "3",
                            "KAST_READ_EXECUTION_MAX_RETURNED_BYTES" to "512",
                        )
                )
                .proven()
        val grant = HostedReadDeadline(limits, { 0L }).admit(null).proven().executionBudget
        assertEquals(2_000L, grant.elapsed.configuredDefault.value)
        assertEquals(800L, grant.elapsed.effective.value)
        assertEquals(12L, grant.work.effective.value)
        assertEquals(3, grant.results.effective.value)
        assertEquals(512L, grant.returnedBytes.effective.value)
        assertTrue(grant.results.clamping.contains(ExecutionBudgetClamp.OPERATOR_CEILING))
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
