package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DependencyCaptureBudgetTest {
    private val resources =
        ResourceBudget(
            (ResultLimit.parse(1) as Refinement.Refined).value,
            (WorkUnitLimit.parse(2) as Refinement.Refined).value,
            (ElapsedTimeLimitMillis.parse(1) as Refinement.Refined).value,
        )

    @Test
    fun `work rejects before extra I O and preserves work on failure`() {
        var cancellations = 0
        val budget = DependencyCaptureBudget(resources, { 0L }, { cancellations++ })
        assertEquals(Refinement.Refined(Unit), budget.step())
        assertEquals(Refinement.Refined(Unit), budget.step())
        assertEquals(Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED), budget.step())
        assertEquals(2L, budget.cost().workUnits)
        assertEquals(3, cancellations)
    }

    @Test
    fun `elapsed boundary rejects without a sleep or consuming work`() {
        var now = 0L
        val budget = DependencyCaptureBudget(resources, { now }, {})
        now = 1_000_000L
        assertEquals(Refinement.Rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED), budget.step())
        assertEquals(0L, budget.cost().workUnits)
        assertEquals(1_000_000L, budget.cost().elapsedNanos)
    }

    @Test
    fun `preempted capture debits spent work once before the failure escapes`() {
        val budget = DependencyCaptureBudget(resources, { 0L }, {})
        val costs = mutableListOf<Long>()
        val failure = IllegalStateException("scripted read preemption")
        val observed =
            assertThrows<IllegalStateException> {
                observeDependencyCaptureCost(budget, { costs += it.workUnits }) {
                    budget.step()
                    throw failure
                }
            }
        assertEquals(failure, observed)
        assertEquals(listOf(1L), costs)
    }

    @Test
    fun `successful capture debits cost once and preserves the captured value`() {
        val budget = DependencyCaptureBudget(resources, { 0L }, {})
        val costs = mutableListOf<Long>()
        val result =
            observeDependencyCaptureCost(budget, { costs += it.workUnits }) {
                budget.step()
                budget.step()
                "completed"
            }
        assertEquals("completed", result)
        assertEquals(listOf(2L), costs)
    }
}
