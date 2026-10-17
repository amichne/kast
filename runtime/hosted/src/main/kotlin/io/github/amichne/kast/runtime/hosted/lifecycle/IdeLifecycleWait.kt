package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspacePreparationBudget
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadline
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Existing lifecycle wait bounds; peer reads do not use this lifecycle effect. */
internal suspend fun awaitLifecycleCondition(
    deadline: WorkspacePreparationDeadline =
        WorkspacePreparationDeadline.start(WorkspacePreparationBudget.limit, System.nanoTime()),
    nanoTime: () -> Long = System::nanoTime,
    condition: () -> Boolean,
): Boolean {
    val remaining =
        when (val admitted = deadline.remaining(nanoTime())) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return false
        }
    return withTimeoutOrNull(remaining.value) {
        while (!condition()) delay(POLL_MILLIS)
        true
    } ?: false
}

private const val POLL_MILLIS = 100L
