package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.source.contract.SourceReadLimitation
import java.util.concurrent.TimeUnit

internal enum class SourceExecutionAdmission {
    ADMITTED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    CLOCK_REJECTED,
}

/** Request-local accounting; detached facts remain owned by each individual read attempt. */
internal class IntellijSourceExecution(
    private val resources: ResourceBudget,
    private val clock: () -> Long = System::nanoTime,
) {
    private val startedAt = clock()
    private var lastObserved = startedAt
    private var timing = SourceExecutionAdmission.ADMITTED
    private var examined = 0L

    fun admitUnit(): SourceExecutionAdmission {
        val time = observeCompletion()
        if (time != SourceExecutionAdmission.ADMITTED) return time
        if (examined == resources.workUnitLimit.value) {
            return SourceExecutionAdmission.WORK_LIMIT_REACHED
        }
        examined += 1
        return SourceExecutionAdmission.ADMITTED
    }

    fun finish(page: IntellijSourceEntityPage): IntellijSourceEntityPage =
        if (page is IntellijSourceEntityPage.Rejected) page
        else
            when (observeCompletion()) {
                SourceExecutionAdmission.ADMITTED -> page
                SourceExecutionAdmission.TIME_LIMIT_REACHED ->
                    page.withLimitation(SourceReadLimitation.TIME_LIMIT_REACHED)
                SourceExecutionAdmission.WORK_LIMIT_REACHED ->
                    page.withLimitation(SourceReadLimitation.WORK_LIMIT_REACHED)
                SourceExecutionAdmission.CLOCK_REJECTED ->
                    IntellijSourceEntityPage.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }

    fun observeCompletion(): SourceExecutionAdmission {
        if (timing != SourceExecutionAdmission.ADMITTED) return timing
        val now = clock()
        val elapsed = now - startedAt
        timing =
            when {
                now - lastObserved < 0L || elapsed < 0L -> SourceExecutionAdmission.CLOCK_REJECTED
                TimeUnit.NANOSECONDS.toMillis(elapsed) >= resources.elapsedTimeLimit.value ->
                    SourceExecutionAdmission.TIME_LIMIT_REACHED
                else -> SourceExecutionAdmission.ADMITTED
            }
        lastObserved = now
        return timing
    }
}
