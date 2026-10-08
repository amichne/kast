package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilation
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection

private const val SCOPED_REACQUISITION_LOOKUP_WORK = 1L
private const val SCOPED_REACQUISITION_NANOS_PER_MILLISECOND = 1_000_000L

/** One scoped exact lookup shares the original grant with owning-document validation. */
internal fun confirmScopedExactReacquisition(
    budget: ResourceBudget,
    clock: IntellijReadNanoClock,
    captureWork: () -> Long,
    checkContent: (WorkUnitLimit) -> Refinement<Unit, ExactRevalidationRejection>,
    confirmCompiler: () -> ExactRevalidationCompilation,
    chargeLookup: () -> Unit,
): ExactRevalidationCompilation {
    val started = clock.now()
    val initialCaptureWork = captureWork()
    val contentGrant =
        when (val admitted = budget.scopedCaptureGrant(clock.now() - started)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return ExactRevalidationCompilation.Rejected(admitted.failure)
        }
    when (val checked = checkContent(contentGrant)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return ExactRevalidationCompilation.Rejected(checked.failure)
    }
    when (val admitted = budget.admitScopedLookup(captureWork() - initialCaptureWork, clock.now() - started)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return ExactRevalidationCompilation.Rejected(admitted.failure)
    }
    chargeLookup()
    return when (val result = confirmCompiler()) {
        is ExactRevalidationCompilation.Rejected -> result
        is ExactRevalidationCompilation.Confirmed ->
            if (budget.scopedTimeExhausted(clock.now() - started))
                ExactRevalidationCompilation.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED)
            else result
    }
}

private fun ResourceBudget.scopedCaptureGrant(
    elapsedNanos: Long
): Refinement<WorkUnitLimit, ExactRevalidationRejection> {
    if (scopedTimeExhausted(elapsedNanos)) return Refinement.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED)
    return when (val limit = WorkUnitLimit.parse(workUnitLimit.value - SCOPED_REACQUISITION_LOOKUP_WORK)) {
        is Refinement.Refined -> limit
        is Refinement.Rejected -> Refinement.Rejected(ExactRevalidationRejection.WORK_LIMIT_REACHED)
    }
}

private fun ResourceBudget.admitScopedLookup(
    captureWork: Long,
    elapsedNanos: Long,
): Refinement<Unit, ExactRevalidationRejection> =
    when {
        scopedTimeExhausted(elapsedNanos) -> Refinement.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED)
        captureWork >= workUnitLimit.value -> Refinement.Rejected(ExactRevalidationRejection.WORK_LIMIT_REACHED)
        else -> Refinement.Refined(Unit)
    }

private fun ResourceBudget.scopedTimeExhausted(elapsedNanos: Long): Boolean =
    elapsedNanos / SCOPED_REACQUISITION_NANOS_PER_MILLISECOND >= elapsedTimeLimit.value
