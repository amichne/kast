package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.protocol.ReadReacquisitionBudgetFailure

/** Request-local effect accounting; reacquisition and semantic execution share the original grant. */
internal class ReadAcquisitionAccounting {
    private var work = 0L
    private var elapsed = 0L

    fun record(examinedWork: Long, elapsedNanos: Long) {
        require(examinedWork >= 0L && elapsedNanos >= 0L)
        work = saturatedAdd(work, examinedWork)
        elapsed =
            saturatedAdd(
                elapsed,
                elapsedNanos / NANOS_PER_MILLISECOND + if (elapsedNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L,
            )
    }

    fun remaining(budget: ResourceBudget): Refinement<ResourceBudget, ReadReacquisitionBudgetFailure> {
        val remainingWork =
            when (val parsed = WorkUnitLimit.parse(budget.workUnitLimit.value - work)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED)
            }
        val remainingTime =
            when (val parsed = ElapsedTimeLimitMillis.parse(budget.elapsedTimeLimit.value - elapsed)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED)
            }
        return Refinement.Refined(budget.copy(workUnitLimit = remainingWork, elapsedTimeLimit = remainingTime))
    }
}

private fun saturatedAdd(left: Long, right: Long) = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private const val NANOS_PER_MILLISECOND = 1_000_000L
