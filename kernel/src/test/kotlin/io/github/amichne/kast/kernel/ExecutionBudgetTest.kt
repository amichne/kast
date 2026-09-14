package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExecutionBudgetTest {
    @Test
    fun `caller allowances are clamped independently and retain selection evidence`() {
        val request = RequestedExecutionBudget(
            ExecutionAllowance.Requested(ElapsedTimeLimitMillis.parse(500).proven()),
            ExecutionAllowance.Requested(WorkUnitLimit.parse(500).proven()),
            ExecutionAllowance.Requested(ResultLimit.parse(500).proven()),
            ExecutionAllowance.Requested(ReturnedByteLimit.parse(500).proven()),
        )
        val grant = AdmittedExecutionBudget.admit(request, resources(100), bytes(100), resources(400), bytes(400),
            ExecutionBudgetCapacity(ElapsedTimeLimitMillis.parse(300).proven(), ResultLimit.parse(200).proven(), bytes(250)))
        assertEquals(300L, grant.resources.elapsedTimeLimit.value)
        assertEquals(400L, grant.resources.workUnitLimit.value)
        assertEquals(200, grant.resources.resultLimit.value)
        assertEquals(250L, grant.returnedBytes.effective.value)
        assertEquals(setOf(ExecutionBudgetClamp.OPERATOR_CEILING, ExecutionBudgetClamp.DEADLINE_REMAINING), grant.elapsed.clamping)
        assertEquals(setOf(ExecutionBudgetClamp.OPERATOR_CEILING), grant.work.clamping)
        assertEquals(setOf(ExecutionBudgetClamp.OPERATOR_CEILING, ExecutionBudgetClamp.TRANSPORT_CAPACITY), grant.results.clamping)
        assertEquals(request.results, grant.results.requested)
    }

    @Test
    fun `absent values select configured defaults and smaller caller values are never ignored`() {
        val capacity = ExecutionBudgetCapacity(ElapsedTimeLimitMillis.parse(1_000).proven(), ResultLimit.parse(1_000).proven(), bytes(1_000))
        val default = AdmittedExecutionBudget.admit(RequestedExecutionBudget(), resources(100), bytes(100), resources(400), bytes(400), capacity)
        assertEquals(resources(100), default.resources)
        val small = AdmittedExecutionBudget.admit(RequestedExecutionBudget(work = ExecutionAllowance.Requested(WorkUnitLimit.parse(1).proven())),
            resources(100), bytes(100), resources(400), bytes(400), capacity)
        assertEquals(1L, small.work.effective.value)
        assertEquals(emptySet<ExecutionBudgetClamp>(), small.work.clamping)
    }

    private fun resources(value: Int) = ResourceBudget(ResultLimit.parse(value).proven(), WorkUnitLimit.parse(value.toLong()).proven(), ElapsedTimeLimitMillis.parse(value.toLong()).proven())
    private fun bytes(value: Long) = ReturnedByteLimit.parse(value).proven()
    private fun <T> Refinement<T, *>.proven(): T = (this as Refinement.Refined).value
}
