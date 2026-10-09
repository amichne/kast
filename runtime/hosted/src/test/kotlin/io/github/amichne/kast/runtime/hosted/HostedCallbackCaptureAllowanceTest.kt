package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureCost
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureCostFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedCallbackCaptureAllowanceTest : HostedSemanticFactFixture() {
    @Test
    fun `aggregate capture cost and current parent both constrain the next grant`() {
        val allowance = HostedCallbackCaptureAllowance(budget(40, 100))
        allowance.record(cost(8, 2_000_000))
        allowance.record(cost(3, 500_000))
        assertEquals(budget(29, 97), allowance.remaining(budget(200, 200)).value())
        assertEquals(budget(12, 7), allowance.remaining(budget(12, 7)).value())
    }

    @Test
    fun `a spent partial millisecond is never regranted`() {
        val allowance = HostedCallbackCaptureAllowance(budget(4, 2))
        allowance.record(cost(0, 1))
        assertEquals(budget(4, 1), allowance.remaining(budget(4, 2)).value())
        allowance.record(cost(0, 999_999))
        assertEquals(budget(4, 1), allowance.remaining(budget(4, 2)).value())
        allowance.record(cost(0, 1))
        assertEquals(
            Refinement.Rejected(HostedCallbackCaptureBudgetFailure.TIME_EXHAUSTED),
            allowance.remaining(budget(4, 2)),
        )
    }

    @Test
    fun `exact work and time exhaustion are finite rejections`() {
        val work = HostedCallbackCaptureAllowance(budget(4, 2))
        work.record(cost(4, 0))
        assertEquals(
            Refinement.Rejected(HostedCallbackCaptureBudgetFailure.WORK_EXHAUSTED),
            work.remaining(budget(4, 2)),
        )
        val time = HostedCallbackCaptureAllowance(budget(4, 2))
        time.record(cost(0, 2_000_000))
        assertEquals(
            Refinement.Rejected(HostedCallbackCaptureBudgetFailure.TIME_EXHAUSTED),
            time.remaining(budget(4, 2)),
        )
    }

    @Test
    fun `either meter overflowing remains rejected after subsequent observations`() {
        for (first in listOf(cost(Long.MAX_VALUE, 0), cost(0, Long.MAX_VALUE))) {
            val allowance = HostedCallbackCaptureAllowance(budget(Long.MAX_VALUE, Long.MAX_VALUE))
            allowance.record(first)
            allowance.record(cost(1, 1))
            allowance.record(cost(0, 0))
            assertEquals(
                Refinement.Rejected(HostedCallbackCaptureBudgetFailure.METER_SATURATED),
                allowance.remaining(budget(Long.MAX_VALUE, Long.MAX_VALUE)),
            )
        }
    }

    @Test
    fun `measured cost rejects negative boundary values and preserves zero`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureCostFailure.NEGATIVE_WORK),
            SemanticDependencyCaptureCost.fromBoundary(-1, 0),
        )
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureCostFailure.NEGATIVE_ELAPSED),
            SemanticDependencyCaptureCost.fromBoundary(0, -1),
        )
        val zero = cost(0, 0)
        assertEquals(0L, zero.workUnits)
        assertEquals(0L, zero.elapsedNanos)
    }

    private fun cost(work: Long, nanos: Long) = SemanticDependencyCaptureCost.fromBoundary(work, nanos).value()

    private fun budget(work: Long, millis: Long) =
        ResourceBudget(
            ResultLimit.parse(8).value(),
            WorkUnitLimit.parse(work).value(),
            ElapsedTimeLimitMillis.parse(millis).value(),
        )
}
