package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationWorkCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class RelationPreparationAccountingTest {
    private val budget =
        ResourceBudget(
            (ResultLimit.parse(1) as Refinement.Refined).value,
            (WorkUnitLimit.parse(4) as Refinement.Refined).value,
            (ElapsedTimeLimitMillis.parse(1) as Refinement.Refined).value,
        )

    @Test
    fun `native preparation spends the same work allowance as callback restoration`() {
        val allowance = IntellijRelationAllowance { 0L }
        allowance.chargePreparation((RelationWorkCount.parse(3) as Refinement.Refined).value)
        assertEquals(CallbackWorkAdmission.READY, allowance.admitCallbackWork(budget))
        assertEquals(CallbackWorkAdmission.WORK_LIMIT_REACHED, allowance.admitCallbackWork(budget))
        assertEquals(4L, allowance.examined)
    }

    @Test
    fun `preparation elapsed time survives native read admission`() {
        var now = 0L
        val allowance = IntellijRelationAllowance { now }
        now = 1_000_000L
        allowance.chargePreparation((RelationWorkCount.parse(0) as Refinement.Refined).value)
        assertEquals(CallbackWorkAdmission.TIME_LIMIT_REACHED, allowance.admitCallbackWork(budget))
        assertEquals(0L, allowance.examined)
    }

    @Test
    fun `read retry receives remaining work and never resets preparation allowance`() {
        val allowance = IntellijRelationAllowance { 0L }
        allowance.chargePreparation((RelationWorkCount.parse(3) as Refinement.Refined).value)
        val remaining = assertInstanceOf<Refinement.Refined<ResourceBudget>>(allowance.remainingResources(budget)).value
        assertEquals(1L, remaining.workUnitLimit.value)
        allowance.chargePreparation((RelationWorkCount.parse(1) as Refinement.Refined).value)
        assertInstanceOf<Refinement.Rejected<*>>(allowance.remainingResources(budget))
    }
}
