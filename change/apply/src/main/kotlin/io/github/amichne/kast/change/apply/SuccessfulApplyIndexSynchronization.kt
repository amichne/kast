package io.github.amichne.kast.change.apply

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

enum class AppliedIndexSynchronizationScheduleFailure {
    EXECUTOR_UNAVAILABLE,
}
/** Closed scheduling result; application truth is intentionally not part of this state. */
sealed interface AppliedIndexSynchronizationSchedule {
    data object Scheduled : AppliedIndexSynchronizationSchedule
    data object Coalesced : AppliedIndexSynchronizationSchedule
    data class Rejected(
        val failure: AppliedIndexSynchronizationScheduleFailure,
    ) : AppliedIndexSynchronizationSchedule
}

fun interface AppliedIndexSynchronizationScheduler {
    fun schedule(): AppliedIndexSynchronizationSchedule
}

fun interface AppliedIndexSynchronizationTask {
    fun synchronize()
}

/** At most one asynchronous synchronization is queued or executing at a time. */
class CoalescingAppliedIndexSynchronizationScheduler(
    private val executor: Executor,
    private val task: AppliedIndexSynchronizationTask,
) : AppliedIndexSynchronizationScheduler {
    private val active = AtomicBoolean(false)

    override fun schedule(): AppliedIndexSynchronizationSchedule {
        if (!active.compareAndSet(false, true)) {
            return AppliedIndexSynchronizationSchedule.Coalesced
        }
        return try {
            executor.execute {
                try {
                    task.synchronize()
                } finally {
                    active.set(false)
                }
            }
            AppliedIndexSynchronizationSchedule.Scheduled
        } catch (_: RejectedExecutionException) {
            active.set(false)
            AppliedIndexSynchronizationSchedule.Rejected(
                AppliedIndexSynchronizationScheduleFailure.EXECUTOR_UNAVAILABLE,
            )
        }
    }
}

/** Decorates only the truthful physical-success variant with best-effort scheduling. */
class SuccessfulApplyIndexSynchronization(
    private val delegate: AddDeclarationApplyOperations,
    private val scheduler: AppliedIndexSynchronizationScheduler,
) : AddDeclarationApplyOperations {
    override fun apply(request: AddDeclarationApplyRequest): AddDeclarationApplyResult {
        val result = delegate.apply(request)
        if (result is AppliedUnverified) {
            try {
                scheduler.schedule()
            } catch (_: RuntimeException) {
                // The source was already durably applied; scheduling cannot rewrite that truth.
            }
        }
        return result
    }
}
