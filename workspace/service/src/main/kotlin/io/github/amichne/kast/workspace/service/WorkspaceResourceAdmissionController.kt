package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceAdmissionWaitMillis
import io.github.amichne.kast.workspace.contract.WorkspaceEdtLiveness
import io.github.amichne.kast.workspace.contract.WorkspaceExpensiveWork
import io.github.amichne.kast.workspace.contract.WorkspaceResourceAdmissionAction
import io.github.amichne.kast.workspace.contract.WorkspaceResourceAdmissionTiming
import io.github.amichne.kast.workspace.contract.WorkspaceResourceBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceResourceDurationNanos
import io.github.amichne.kast.workspace.contract.WorkspaceResourceInitiationResult
import io.github.amichne.kast.workspace.contract.WorkspaceResourceObservation
import io.github.amichne.kast.workspace.contract.WorkspaceResourcePolicy
import io.github.amichne.kast.workspace.spi.WorkspaceResourceObservationAuthority
import java.lang.System as JavaSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun interface WorkspaceResourceInitiation {
    /** Runs only the expensive initiation step; readiness waiting remains outside this callback. */
    fun initiate()
}

fun interface WorkspaceResourceClock {
    /** Reads raw monotonic nanoseconds only at the admission-timing boundary. */
    fun nowNanos(): Long

    companion object {
        val System: WorkspaceResourceClock = WorkspaceResourceClock(JavaSystem::nanoTime)
    }
}

@JvmInline
value class WorkspaceResourceControllerCount internal constructor(
    val value: Int,
)

data class WorkspaceResourceControllerSnapshot(
    val activeStarts: WorkspaceResourceControllerCount,
    val queuedWaiters: WorkspaceResourceControllerCount,
) {
    companion object {
        fun empty(): WorkspaceResourceControllerSnapshot = WorkspaceResourceControllerSnapshot(
            activeStarts = WorkspaceResourceControllerCount(0),
            queuedWaiters = WorkspaceResourceControllerCount(0),
        )
    }
}

/**
 * Coordinates only expensive initiation. Readiness waits and operation execution are deliberately
 * outside this owner so unrelated kinds and ordinary reads cannot be serialized by this lock.
 */
class WorkspaceResourceAdmissionController(
    private val policy: WorkspaceResourcePolicy,
    private val observationAuthority: WorkspaceResourceObservationAuthority,
    private val clock: WorkspaceResourceClock = WorkspaceResourceClock.System,
) {
    private val lock = Any()
    private val active = linkedMapOf<WorkspaceInitiationKey, ActiveWorkspaceInitiation>()
    private var queuedWaiters = 0

    /**
     * Proof transition:
     * `CanonicalWorkspaceRoot + WorkspaceExpensiveWork + WorkspaceResourceInitiation`
     * `-> WorkspaceResourceInitiationResult`.
     *
     * Establishes typed resource admission, one exact-root initiation owner, absolute bounded
     * queueing, and release before readiness wait. [WorkspaceResourceBlocker] is the closed expected
     * admission failure. The raw callback, monotonic clock, latch, and counters remain inside this
     * service boundary; an initiation exception is released exactly once and then rethrown.
     */
    fun coordinate(
        root: CanonicalWorkspaceRoot,
        kind: WorkspaceExpensiveWork,
        initiation: WorkspaceResourceInitiation,
    ): WorkspaceResourceInitiationResult {
        val requestedAt = clock.nowNanos()
        var queueDuration = WorkspaceResourceDurationNanos.zero()
        val key = WorkspaceInitiationKey(root, kind)
        while (true) {
            when (val exact = exactInitiation(key)) {
                WorkspaceExactInitiation.Absent -> Unit
                is WorkspaceExactInitiation.Present ->
                    return waitForExact(exact.entry, requestedAt, queueDuration)
            }
            val observation = observationAuthority.observe()
            when (val claim = claim(key, observation)) {
                is WorkspaceInitiationClaim.Start ->
                    return initiate(claim.entry, initiation, requestedAt, queueDuration)
                is WorkspaceInitiationClaim.Reuse ->
                    return waitForExact(claim.entry, requestedAt, queueDuration)
                is WorkspaceInitiationClaim.Queue -> {
                    when (val waited = waitForCapacity(claim.entry, requestedAt, queueDuration)) {
                        is WorkspaceCapacityWait.Retry -> queueDuration = waited.queueDuration
                        is WorkspaceCapacityWait.Complete -> return waited.result
                    }
                }
                is WorkspaceInitiationClaim.Rejected ->
                    return rejected(claim.blocker, claim.action, requestedAt, queueDuration)
            }
        }
    }

    /** Returns detached controller pressure without exposing roots, latches, or initiation state. */
    fun snapshot(): WorkspaceResourceControllerSnapshot = synchronized(lock) {
        WorkspaceResourceControllerSnapshot(
            activeStarts = WorkspaceResourceControllerCount(active.size),
            queuedWaiters = WorkspaceResourceControllerCount(queuedWaiters),
        )
    }

    private fun exactInitiation(key: WorkspaceInitiationKey): WorkspaceExactInitiation =
        synchronized(lock) {
            if (key in active) {
                WorkspaceExactInitiation.Present(active.getValue(key))
            } else {
                WorkspaceExactInitiation.Absent
            }
        }

    private fun claim(
        key: WorkspaceInitiationKey,
        observation: WorkspaceResourceObservation,
    ): WorkspaceInitiationClaim = synchronized(lock) {
        if (key in active) {
            return@synchronized WorkspaceInitiationClaim.Reuse(active.getValue(key))
        }
        if (observation.heap.value >= policy.criticalHeap.value) {
            return@synchronized WorkspaceInitiationClaim.Rejected(
                WorkspaceResourceBlocker.HeapCritical(observation.heap, policy.criticalHeap),
                WorkspaceResourceAdmissionAction.RECOVER_HEAP,
            )
        }
        if (observation.edt != WorkspaceEdtLiveness.Live) {
            return@synchronized WorkspaceInitiationClaim.Rejected(
                WorkspaceResourceBlocker.EdtUnavailable(observation.edt),
                WorkspaceResourceAdmissionAction.RECOVER_EDT,
            )
        }
        val conflicts = active.values.filter { entry -> entry.key.kind == key.kind }
        val observed = observation.activity.active(key.kind).value.toLong()
        if (observed + conflicts.size.toLong() >= policy.limitFor(key.kind).value.toLong()) {
            return@synchronized if (conflicts.isEmpty()) {
                WorkspaceInitiationClaim.Rejected(
                    WorkspaceResourceBlocker.Capacity(key.kind, policy.limitFor(key.kind)),
                    WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE,
                )
            } else {
                WorkspaceInitiationClaim.Queue(conflicts.first())
            }
        }
        val entry = ActiveWorkspaceInitiation(key)
        active[key] = entry
        WorkspaceInitiationClaim.Start(entry)
    }

    private fun initiate(
        entry: ActiveWorkspaceInitiation,
        initiation: WorkspaceResourceInitiation,
        requestedAt: Long,
        queueDuration: WorkspaceResourceDurationNanos,
    ): WorkspaceResourceInitiationResult {
        try {
            initiation.initiate()
            entry.complete(WorkspaceInitiationCompletion.Succeeded)
            return WorkspaceResourceInitiationResult.Initiated(
                timing(requestedAt, queueDuration),
            )
        } catch (failure: Throwable) {
            entry.complete(WorkspaceInitiationCompletion.Failed)
            throw failure
        } finally {
            synchronized(lock) {
                if (active[entry.key] === entry) {
                    active.remove(entry.key)
                }
            }
        }
    }

    private fun waitForExact(
        entry: ActiveWorkspaceInitiation,
        requestedAt: Long,
        queueDuration: WorkspaceResourceDurationNanos,
    ): WorkspaceResourceInitiationResult = when (
        val waited = await(entry, requestedAt, queueDuration)
    ) {
        is WorkspaceEntryWait.Completed -> when (entry.completion()) {
            WorkspaceInitiationCompletion.Succeeded ->
                WorkspaceResourceInitiationResult.ReusedExactRoot(
                    timing(requestedAt, waited.queueDuration),
                )
            WorkspaceInitiationCompletion.Failed,
            WorkspaceInitiationCompletion.Running,
                ->
                rejected(
                    WorkspaceResourceBlocker.InitiationFailed(entry.key.kind),
                    WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE,
                    requestedAt,
                    waited.queueDuration,
                )
        }
        is WorkspaceEntryWait.Rejected -> waited.result
    }

    private fun waitForCapacity(
        entry: ActiveWorkspaceInitiation,
        requestedAt: Long,
        queueDuration: WorkspaceResourceDurationNanos,
    ): WorkspaceCapacityWait = when (val waited = await(entry, requestedAt, queueDuration)) {
        is WorkspaceEntryWait.Completed ->
            WorkspaceCapacityWait.Retry(waited.queueDuration)
        is WorkspaceEntryWait.Rejected ->
            WorkspaceCapacityWait.Complete(waited.result)
    }

    private fun await(
        entry: ActiveWorkspaceInitiation,
        requestedAt: Long,
        queueDuration: WorkspaceResourceDurationNanos,
    ): WorkspaceEntryWait {
        val elapsed = WorkspaceResourceDurationNanos.elapsed(requestedAt, clock.nowNanos())
        val remaining = policy.waitTimeout.nanoseconds - elapsed.nanoseconds
        if (remaining <= 0L) {
            return WorkspaceEntryWait.Rejected(
                rejected(
                    WorkspaceResourceBlocker.WaitTimedOut(policy.waitTimeout),
                    WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE,
                    requestedAt,
                    queueDuration,
                ),
            )
        }
        if (!reserveWaiter()) {
            return WorkspaceEntryWait.Rejected(
                rejected(
                    WorkspaceResourceBlocker.QueueFull(policy.queuedWaiters),
                    WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE,
                    requestedAt,
                    queueDuration,
                ),
            )
        }
        val waitStarted = clock.nowNanos()
        val completed = try {
            entry.await(remaining)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return WorkspaceEntryWait.Rejected(
                rejected(
                    WorkspaceResourceBlocker.WaitInterrupted,
                    WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE,
                    requestedAt,
                    queueDuration.plus(
                        WorkspaceResourceDurationNanos.elapsed(waitStarted, clock.nowNanos()),
                    ),
                ),
            )
        } finally {
            releaseWaiter()
        }
        val accumulated = queueDuration.plus(
            WorkspaceResourceDurationNanos.elapsed(waitStarted, clock.nowNanos()),
        )
        return if (completed) {
            WorkspaceEntryWait.Completed(accumulated)
        } else {
            WorkspaceEntryWait.Rejected(
                rejected(
                    WorkspaceResourceBlocker.WaitTimedOut(policy.waitTimeout),
                    WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE,
                    requestedAt,
                    accumulated,
                ),
            )
        }
    }

    private fun reserveWaiter(): Boolean = synchronized(lock) {
        if (queuedWaiters >= policy.queuedWaiters.value) {
            false
        } else {
            queuedWaiters += 1
            true
        }
    }

    private fun releaseWaiter() = synchronized(lock) {
        queuedWaiters -= 1
    }

    private fun rejected(
        blocker: WorkspaceResourceBlocker,
        action: WorkspaceResourceAdmissionAction,
        requestedAt: Long,
        queueDuration: WorkspaceResourceDurationNanos,
    ): WorkspaceResourceInitiationResult.Rejected =
        WorkspaceResourceInitiationResult.Rejected(
            blocker = blocker,
            action = action,
            timing = timing(requestedAt, queueDuration),
        )

    private fun timing(
        requestedAt: Long,
        queueDuration: WorkspaceResourceDurationNanos,
    ): WorkspaceResourceAdmissionTiming {
        val total = WorkspaceResourceDurationNanos.elapsed(requestedAt, clock.nowNanos())
        return WorkspaceResourceAdmissionTiming(
            queue = queueDuration,
            admission = total.excluding(queueDuration),
        )
    }
}

private data class WorkspaceInitiationKey(
    val root: CanonicalWorkspaceRoot,
    val kind: WorkspaceExpensiveWork,
)

private sealed interface WorkspaceExactInitiation {
    data object Absent : WorkspaceExactInitiation

    data class Present(val entry: ActiveWorkspaceInitiation) : WorkspaceExactInitiation
}

private class ActiveWorkspaceInitiation(
    val key: WorkspaceInitiationKey,
) {
    private val finished = CountDownLatch(1)
    private val state = AtomicReference(WorkspaceInitiationCompletion.Running)

    fun complete(completion: WorkspaceInitiationCompletion) {
        if (state.compareAndSet(WorkspaceInitiationCompletion.Running, completion)) {
            finished.countDown()
        }
    }

    @Throws(InterruptedException::class)
    fun await(timeoutNanos: Long): Boolean =
        finished.await(timeoutNanos, TimeUnit.NANOSECONDS)

    fun completion(): WorkspaceInitiationCompletion = state.get()
}

private enum class WorkspaceInitiationCompletion {
    Running,
    Succeeded,
    Failed,
}

private sealed interface WorkspaceInitiationClaim {
    data class Start(val entry: ActiveWorkspaceInitiation) : WorkspaceInitiationClaim

    data class Reuse(val entry: ActiveWorkspaceInitiation) : WorkspaceInitiationClaim

    data class Queue(val entry: ActiveWorkspaceInitiation) : WorkspaceInitiationClaim

    data class Rejected(
        val blocker: WorkspaceResourceBlocker,
        val action: WorkspaceResourceAdmissionAction,
    ) : WorkspaceInitiationClaim
}

private sealed interface WorkspaceEntryWait {
    data class Completed(
        val queueDuration: WorkspaceResourceDurationNanos,
    ) : WorkspaceEntryWait

    data class Rejected(
        val result: WorkspaceResourceInitiationResult.Rejected,
    ) : WorkspaceEntryWait
}

private sealed interface WorkspaceCapacityWait {
    data class Retry(
        val queueDuration: WorkspaceResourceDurationNanos,
    ) : WorkspaceCapacityWait

    data class Complete(
        val result: WorkspaceResourceInitiationResult.Rejected,
    ) : WorkspaceCapacityWait
}
