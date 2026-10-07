package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedSemanticPreparationBudgetTest {
    @Test
    fun `optional capture leaves three quarters of work and time for extraction`() {
        val original = resources(23, 43)
        val prepared = assertInstanceOf<Refinement.Refined<ResourceBudget>>(optionalPreparationBudget(original)).value
        assertEquals(5L, prepared.workUnitLimit.value)
        assertEquals(10L, prepared.elapsedTimeLimit.value)
        assertEquals(original.resultLimit, prepared.resultLimit)
    }

    @Test
    fun `small grants skip optional capture instead of manufacturing extra budget`() {
        assertInstanceOf<Refinement.Rejected<*>>(optionalPreparationBudget(resources(3, 10)))
        assertInstanceOf<Refinement.Rejected<*>>(optionalPreparationBudget(resources(10, 3)))
    }

    private fun resources(work: Long, time: Long) =
        ResourceBudget(
            (ResultLimit.parse(7) as Refinement.Refined).value,
            (WorkUnitLimit.parse(work) as Refinement.Refined).value,
            (ElapsedTimeLimitMillis.parse(time) as Refinement.Refined).value,
        )
}
