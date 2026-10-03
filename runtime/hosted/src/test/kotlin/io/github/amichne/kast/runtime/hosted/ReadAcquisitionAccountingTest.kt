package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.protocol.ReadReacquisitionBudgetFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadAcquisitionAccountingTest {
    private val budget =
        ResourceBudget(
            ResultLimit.parse(4).value(),
            WorkUnitLimit.parse(10).value(),
            ElapsedTimeLimitMillis.parse(20).value(),
        )

    @Test
    fun `multiple lookups spend the same work and elapsed allowance`() {
        val accounting = ReadAcquisitionAccounting()
        accounting.record(3, 1)
        accounting.record(2, 2_000_001)
        val remaining = accounting.remaining(budget).value()
        assertEquals(5, remaining.workUnitLimit.value)
        assertEquals(16, remaining.elapsedTimeLimit.value)
        assertEquals(budget.resultLimit, remaining.resultLimit)
    }

    @Test
    fun `exhaustion never manufactures a positive semantic allowance`() {
        val work = ReadAcquisitionAccounting()
        work.record(10, 0)
        assertEquals(Refinement.Rejected(ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED), work.remaining(budget))
        val time = ReadAcquisitionAccounting()
        time.record(0, 20_000_000)
        assertEquals(Refinement.Rejected(ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED), time.remaining(budget))
    }

    @Test
    fun `work snapshot stays detached and does not debit the same observation twice`() {
        val accounting = ReadAcquisitionAccounting()
        accounting.record(2, 1)
        val first = accounting.snapshot()
        accounting.record(3, 2_000_001)
        assertEquals(2, first.value)
        assertEquals(5, accounting.snapshot().value)
        val remaining = accounting.remaining(budget).value()
        assertEquals(5, remaining.workUnitLimit.value)
        assertEquals(16, remaining.elapsedTimeLimit.value)
    }

    @Test
    fun `no acquisition produces an exact zero work snapshot`() {
        assertEquals(0, ReadAcquisitionAccounting().snapshot().value)
    }

    private fun <T> Refinement<T, *>.value() = (this as Refinement.Refined).value
}
