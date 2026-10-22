package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureCost

internal enum class HostedCallbackCaptureBudgetFailure {
    WORK_EXHAUSTED,
    TIME_EXHAUSTED,
    METER_SATURATED,
}

/** One optional grant across all lazy captures. Every subsequent grant is clipped to the parent's current allowance. */
internal class HostedCallbackCaptureAllowance(private val initial: ResourceBudget) {
    private var work = 0L
    private var nanos = 0L
    private var meter = Meter.EXACT

    fun record(cost: SemanticDependencyCaptureCost) {
        if (cost.workUnits > Long.MAX_VALUE - work || cost.elapsedNanos > Long.MAX_VALUE - nanos) {
            meter = Meter.SATURATED
            return
        }
        work += cost.workUnits
        nanos += cost.elapsedNanos
    }

    fun remaining(current: ResourceBudget): Refinement<ResourceBudget, HostedCallbackCaptureBudgetFailure> {
        val bounded =
            when (initial) {
                ResourceBudget.RunnerControlled -> return Refinement.Refined(current)
                is ResourceBudget.Bounded -> initial
            }
        if (meter == Meter.SATURATED) return Refinement.Rejected(HostedCallbackCaptureBudgetFailure.METER_SATURATED)
        val workRemaining = bounded.workUnitLimit.value - work
        // Round spent time upwards; truncation grants the same partial millisecond repeatedly.
        val spentMillis = nanos / NANOS_PER_MILLISECOND + if (nanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
        val timeRemaining = bounded.elapsedTimeLimit.value - spentMillis
        val workLimit =
            when (val parsed = WorkUnitLimit.parse(workRemaining)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HostedCallbackCaptureBudgetFailure.WORK_EXHAUSTED)
            }
        val timeLimit =
            when (val parsed = ElapsedTimeLimitMillis.parse(timeRemaining)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HostedCallbackCaptureBudgetFailure.TIME_EXHAUSTED)
            }
        return Refinement.Refined(
            current.intersect(bounded.copy(workUnitLimit = workLimit, elapsedTimeLimit = timeLimit))
        )
    }

    private enum class Meter {
        EXACT,
        SATURATED,
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
