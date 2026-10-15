package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ExecutionBudgetClamp
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedDefaultExecutionBudgetTest {
    @Test
    fun `omitted execution controls admit the larger grant with compact responses`() {
        val grant = HostedReadDeadline(ReadLimits.Default, { 0L }).admit(null).proven().executionBudget
        assertEquals(15_000L, grant.elapsed.effective.value)
        assertEquals(2_000_000L, grant.work.effective.value)
        assertEquals(128, grant.results.effective.value)
        assertEquals(49_152L, grant.returnedBytes.effective.value)
        assertEquals(ExecutionAllowance.Default, grant.work.requested)
        assertEquals(emptySet<ExecutionBudgetClamp>(), grant.work.clamping)
    }

    @Test
    fun `explicit smaller execution controls remain authoritative`() {
        val request =
            HostedExecutionBudgetRequest(
                RequestedExecutionBudget(
                    elapsed = ExecutionAllowance.Requested(ElapsedTimeLimitMillis.parse(2_000).proven()),
                    work = ExecutionAllowance.Requested(WorkUnitLimit.parse(100_000).proven()),
                )
            )
        val grant = HostedReadDeadline(ReadLimits.Default, { 0L }, request).admit(null).proven().executionBudget
        assertEquals(2_000L, grant.elapsed.effective.value)
        assertEquals(100_000L, grant.work.effective.value)
        assertEquals(15_000L, grant.elapsed.configuredDefault.value)
        assertEquals(2_000_000L, grant.work.configuredDefault.value)
        assertEquals(128, grant.results.effective.value)
        assertEquals(49_152L, grant.returnedBytes.effective.value)
        assertEquals(request.requested.work, grant.work.requested)
        assertEquals(emptySet<ExecutionBudgetClamp>(), grant.work.clamping)
    }

    @Test
    fun `remaining host time still limits the default and preserves clamp evidence`() {
        var now = 0L
        val deadline = HostedReadDeadline(ReadLimits.Default, { now })
        now = 29_000_000_000L
        val grant = deadline.admit(null).proven().executionBudget
        assertEquals(750L, grant.elapsed.effective.value)
        assertEquals(setOf(ExecutionBudgetClamp.DEADLINE_REMAINING), grant.elapsed.clamping)
        assertEquals(2_000_000L, grant.work.effective.value)
        now = 29_750_000_000L
        assertEquals(Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), deadline.admit(null))
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
