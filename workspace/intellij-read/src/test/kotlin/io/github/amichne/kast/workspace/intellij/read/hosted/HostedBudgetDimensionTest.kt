package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
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
import org.junit.jupiter.api.Test

class HostedBudgetDimensionTest {
    @Test
    fun `one requested axis leaves all other hosted grants unchanged for both profiles`() {
        for (profile in HostedBudgetProfile.entries) {
            val baseline = grant(HostedExecutionBudgetRequest(profile = profile))
            for (axis in Axis.entries) {
                val input = HostedExecutionBudgetRequest(axis.request(), profile)
                val actual = grant(input)
                val expected = baseline.amounts().toMutableList().apply { this[axis.ordinal] = axis.amount }
                assertEquals(expected, actual.amounts(), "$profile $axis")
                assertEquals(input.requested.elapsed, actual.elapsed.requested)
                assertEquals(input.requested.work, actual.work.requested)
                assertEquals(input.requested.results, actual.results.requested)
                assertEquals(input.requested.returnedBytes, actual.returnedBytes.requested)
                assertEquals(emptySet<ExecutionBudgetClamp>(), actual.clamps()[axis.ordinal], "$profile $axis")
            }
        }
    }

    private fun grant(request: HostedExecutionBudgetRequest): AdmittedExecutionBudget =
        admitHostedExecutionBudget(ReadLimits.Default, request, ElapsedTimeLimitMillis.parse(4_000).proven())

    private fun AdmittedExecutionBudget.amounts() =
        listOf(
            elapsed.effective.value,
            work.effective.value,
            results.effective.value.toLong(),
            returnedBytes.effective.value,
        )

    private fun AdmittedExecutionBudget.clamps() =
        listOf(elapsed.clamping, work.clamping, results.clamping, returnedBytes.clamping)

    private enum class Axis(val amount: Long) {
        TIME(100),
        WORK(5),
        RESULTS(3),
        BYTES(4096);

        fun request(): RequestedExecutionBudget =
            when (this) {
                TIME ->
                    RequestedExecutionBudget(
                        elapsed = ExecutionAllowance.Requested(ElapsedTimeLimitMillis.parse(amount).proven())
                    )
                WORK ->
                    RequestedExecutionBudget(work = ExecutionAllowance.Requested(WorkUnitLimit.parse(amount).proven()))
                RESULTS ->
                    RequestedExecutionBudget(
                        results = ExecutionAllowance.Requested(ResultLimit.parse(amount.toInt()).proven())
                    )
                BYTES ->
                    RequestedExecutionBudget(
                        returnedBytes = ExecutionAllowance.Requested(ReturnedByteLimit.parse(amount).proven())
                    )
            }
    }
}

private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
