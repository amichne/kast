package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijSourceExecutionTest {
    @Test
    fun `work allowance stops before an additional unit including restarted attempts`() {
        val execution = IntellijSourceExecution(resources(work = 2)) { 0L }
        repeat(2) { assertEquals(SourceExecutionAdmission.ADMITTED, execution.admitUnit()) }
        repeat(2) { assertEquals(SourceExecutionAdmission.WORK_LIMIT_REACHED, execution.admitUnit()) }
        assertEquals(SourceExecutionAdmission.ADMITTED, execution.observeCompletion())
    }

    @Test
    fun `admitted caller allowance may exceed the configured default`() {
        val defaults =
            ReadLimits.resolve(environment = mapOf(ReadLimitParameter.SOURCE_ENTITY_WORK.environmentKey to "1"))
                .proven()
        val execution =
            IntellijSourceExecution(resources(work = defaults[ReadLimitParameter.SOURCE_ENTITY_WORK].value + 1L)) { 0L }
        repeat(2) { assertEquals(SourceExecutionAdmission.ADMITTED, execution.admitUnit()) }
        assertEquals(SourceExecutionAdmission.WORK_LIMIT_REACHED, execution.admitUnit())
    }

    @Test
    fun `elapsed admission includes setup and completion observes an actual overrun`() {
        var now = 0L
        val execution = IntellijSourceExecution(resources(time = 2)) { now }
        assertEquals(SourceExecutionAdmission.ADMITTED, execution.admitUnit())
        now = 2_000_000L
        assertEquals(SourceExecutionAdmission.TIME_LIMIT_REACHED, execution.admitUnit())
        now = 5_000_000L
        assertEquals(SourceExecutionAdmission.TIME_LIMIT_REACHED, execution.observeCompletion())
    }

    @Test
    fun `clock regression is a finite rejection`() {
        var now = 10L
        val execution = IntellijSourceExecution(resources()) { now }
        now = 9L
        assertEquals(SourceExecutionAdmission.CLOCK_REJECTED, execution.admitUnit())
        assertEquals(SourceExecutionAdmission.CLOCK_REJECTED, execution.observeCompletion())
    }

    @Test
    fun `completion preserves existing qualification and never replaces rejection with partial evidence`() {
        var now = 0L
        val execution = IntellijSourceExecution(resources(time = 2)) { now }
        val page =
            IntellijSourceEntityPage.Complete(
                emptyList(),
                0,
                setOf(io.github.amichne.kast.source.contract.SourceReadLimitation.UNSUPPORTED_ENTITY),
            )
        now = 2_000_000L
        val finished = execution.finish(page)
        assertEquals(
            setOf(
                io.github.amichne.kast.source.contract.SourceReadLimitation.UNSUPPORTED_ENTITY,
                io.github.amichne.kast.source.contract.SourceReadLimitation.TIME_LIMIT_REACHED,
            ),
            finished.limitations,
        )
        val rejection = IntellijSourceEntityPage.Rejected(IntellijSourceReadRejection.STALE_GENERATION)
        assertEquals(rejection, execution.finish(rejection))
    }

    private fun resources(work: Long = 100, time: Long = 100): ResourceBudget =
        ResourceBudget(
            ResultLimit.parse(10).proven(),
            WorkUnitLimit.parse(work).proven(),
            ElapsedTimeLimitMillis.parse(time).proven(),
        )

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
