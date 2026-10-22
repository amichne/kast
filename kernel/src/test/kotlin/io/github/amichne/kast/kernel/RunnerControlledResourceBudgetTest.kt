package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerControlledResourceBudgetTest {
    @Test
    fun `runner controlled execution admits counters without finite thresholds`() {
        val budget = ResourceBudget.RunnerControlled
        assertTrue(budget.permitsResults(Int.MAX_VALUE))
        assertTrue(budget.hasResultCapacity(Int.MAX_VALUE))
        assertTrue(budget.permitsWork(Long.MAX_VALUE))
        assertTrue(budget.hasWorkCapacity(Long.MAX_VALUE))
        assertTrue(budget.permitsElapsedMillis(Long.MAX_VALUE))
        assertTrue(budget.hasTimeRemaining(Long.MAX_VALUE))
    }

    @Test
    fun `bounded execution keeps exact inclusive counts and exclusive time exhaustion`() {
        val budget = bounded()
        assertTrue(budget.permitsResults(1))
        assertFalse(budget.permitsResults(2))
        assertFalse(budget.hasResultCapacity(1))
        assertTrue(budget.permitsWork(1))
        assertFalse(budget.permitsWork(2))
        assertFalse(budget.hasWorkCapacity(1))
        assertTrue(budget.permitsElapsedMillis(1))
        assertFalse(budget.permitsElapsedMillis(2))
        assertFalse(budget.hasTimeRemaining(1))
    }

    @Test
    fun `a bounded child cannot grant runner controlled authority`() {
        val bounded = bounded()
        assertTrue(bounded.fitsWithin(ResourceBudget.RunnerControlled))
        assertFalse(ResourceBudget.RunnerControlled.fitsWithin(bounded))
        assertTrue(ResourceBudget.RunnerControlled.fitsWithin(ResourceBudget.RunnerControlled))
    }

    private fun bounded(): ResourceBudget.Bounded =
        ResourceBudget(
            ResultLimit.parse(1).proven(),
            WorkUnitLimit.parse(1).proven(),
            ElapsedTimeLimitMillis.parse(1).proven(),
        )
}

private fun <V> Refinement<V, *>.proven(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
