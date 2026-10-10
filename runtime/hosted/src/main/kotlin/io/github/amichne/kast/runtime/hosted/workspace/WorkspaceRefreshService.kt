package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason

/** Project-owned transitions and one thin native effect boundary, independent of semantic request budgets. */
internal class WorkspaceRefreshService(
    private val port: WorkspaceRefreshPort,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pendingTimeoutNanos: Long = 120_000_000_000L,
    private val capacity: Int = 64,
) {
    init {
        require(pendingTimeoutNanos > 0)
        require(capacity > 0)
    }

    private val entries = linkedMapOf<WorkspaceRefreshWaiter, WorkspaceRefreshEntry>()
    private val queue = ArrayDeque<WorkspaceRefreshAttempt>()
    private var active: WorkspaceRefreshAttempt? = null
    private var disposed = false
    private var latestStamp = 0L
    private var latestAttempt = 0L

    @Synchronized
    fun submit(id: WorkspaceRefreshRequestId, effect: WorkspaceRefreshEffect): WorkspaceRefreshStatus {
        entries[WorkspaceRefreshWaiter.Explicit(id)]?.let {
            return submit(id, effect, it.attempt.work.stamp)
        }
        if (disposed) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.DISPOSED)
        expire()
        observeAdmission()
        val observed = port.readiness() as? WorkspaceCapabilityReadiness.Ready
        val candidates =
            listOfNotNull(active) +
                queue +
                entries.values
                    .filter {
                        it.status is WorkspaceRefreshStatus.Pending
                    }
                    .map { it.attempt }
        val equivalent = candidates.equivalentModelDemand(effect, observed)
        return if (equivalent != null) submit(id, effect, equivalent.work.stamp, observed)
        else
            when (val stamp = nextStamp()) {
                is Refinement.Refined -> submit(id, effect, stamp.value, observed)
                is Refinement.Rejected -> WorkspaceRefreshStatus.Rejected(stamp.failure)
            }
    }

    @Synchronized
    fun submit(
        id: WorkspaceRefreshRequestId,
        effect: WorkspaceRefreshEffect,
        stamp: WorkspaceRefreshStamp,
        observation: WorkspaceCapabilityReadiness.Ready? = null,
    ): WorkspaceRefreshStatus {
        if (disposed) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.DISPOSED)
        val work = WorkspaceRefreshWork(WorkspaceRefreshEffectKind.Explicit(effect), stamp)
        val waiter = WorkspaceRefreshWaiter.Explicit(id)
        entries[waiter]?.let { previous ->
            if (previous.attempt.work != work)
                return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.REQUEST_CONFLICT)
            return status(id)
        }
        expire()
        observeAdmission()
        if (!makeRoom()) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.CAPACITY)
        latestStamp = maxOf(latestStamp, stamp.value)
        when (val added = add(waiter, work, observation = observation)) {
            is Refinement.Rejected -> return WorkspaceRefreshStatus.Rejected(added.failure)
            is Refinement.Refined -> Unit
        }
        advance()
        return entries.getValue(waiter).status
    }

    /** Incremental read preparation joins equivalent demand and leaves final semantic admission to the query owner. */
    @Synchronized
    fun refreshForRead(complete: (WorkspaceRefreshStatus) -> Unit): () -> Unit {
        if (disposed) {
            complete(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED))
            return {}
        }
        expire()
        observeAdmission()
        if (!makeRoom()) {
            complete(WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.CAPACITY))
            return {}
        }
        val equivalent =
            entries.values.firstOrNull {
                it.attempt.work.effect == WorkspaceRefreshEffectKind.Incremental &&
                    it.status is WorkspaceRefreshStatus.Pending
            }
        val work =
            equivalent?.attempt?.work
                ?: when (val stamp = nextStamp()) {
                    is Refinement.Refined -> WorkspaceRefreshWork(WorkspaceRefreshEffectKind.Incremental, stamp.value)
                    is Refinement.Rejected -> {
                        complete(WorkspaceRefreshStatus.Rejected(stamp.failure))
                        return {}
                    }
                }
        val waiter = WorkspaceRefreshWaiter.Read()
        when (val added = add(waiter, work, complete)) {
            is Refinement.Rejected -> {
                complete(WorkspaceRefreshStatus.Rejected(added.failure))
                return {}
            }
            is Refinement.Refined -> Unit
        }
        advance()
        return { cancel(waiter) }
    }

    private fun nextStamp(): Refinement<WorkspaceRefreshStamp, WorkspaceRefreshRejection> =
        if (latestStamp == Long.MAX_VALUE) Refinement.Rejected(WorkspaceRefreshRejection.CAPACITY)
        else WorkspaceRefreshStamp.parse(++latestStamp)

    private fun add(
        waiter: WorkspaceRefreshWaiter,
        work: WorkspaceRefreshWork,
        complete: (WorkspaceRefreshStatus) -> Unit = {},
        observation: WorkspaceCapabilityReadiness.Ready? = null,
    ): Refinement<Unit, WorkspaceRefreshRejection> {
        val equivalent =
            entries.values.firstOrNull {
                it.attempt.work == work && it.status is WorkspaceRefreshStatus.Pending
            }
        val attempt =
            equivalent?.attempt
                ?: run {
                    if (latestAttempt == Long.MAX_VALUE) return Refinement.Rejected(WorkspaceRefreshRejection.CAPACITY)
                    WorkspaceRefreshAttempt(
                            work,
                            WorkspaceRefreshAttemptId.fromSequence(++latestAttempt),
                            waiter.identity(),
                            observation,
                        )
                        .also(queue::addLast)
                }
        entries[waiter] =
            WorkspaceRefreshEntry(
                attempt,
                nanoTime(),
                equivalent?.status ?: WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.QUEUED),
                complete,
            )
        return Refinement.Refined(Unit)
    }

    @Synchronized
    fun inspection(): WorkspaceRefreshInspection {
        expire()
        if (disposed) return WorkspaceRefreshInspection.Retired(listOfNotNull(active?.inspection(entries)))
        active?.let {
            return WorkspaceRefreshInspection.Running(
                it.inspection(entries),
                queue.map { queued -> queued.inspection(entries) },
            )
        }
        val admission = entries.filterValues {
            it.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION)
        }
        if (admission.isNotEmpty())
            return WorkspaceRefreshInspection.AwaitingAdmission(
                admission.map { (waiter, entry) -> WorkspaceRefreshWaiterInspection(waiter.identity(), entry.status) }
            )
        return WorkspaceRefreshInspection.Idle
    }

    @Synchronized
    private fun cancel(waiter: WorkspaceRefreshWaiter) {
        entries[waiter]?.let { entry ->
            if (entry.status is WorkspaceRefreshStatus.Pending)
                transition(entry, WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED))
        }
        pruneQueue()
    }

    @Synchronized
    fun status(id: WorkspaceRefreshRequestId): WorkspaceRefreshStatus {
        val entry =
            entries[WorkspaceRefreshWaiter.Explicit(id)]
                ?: return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.UNKNOWN_REQUEST)
        expire()
        advance()
        observeAdmission()
        return entry.status
    }

    private fun observeAdmission() {
        if (disposed || active != null || queue.isNotEmpty()) return
        if (entries.values.none { it.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION) }) return
        // Always reobserve through the native owner after effect settlement. No retained ready flag.
        when (val observed = port.readiness()) {
            is WorkspaceCapabilityReadiness.Ready ->
                entries.values.toList().forEach {
                    if (it.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION))
                        transition(it, WorkspaceRefreshStatus.Complete)
                }
            is WorkspaceCapabilityReadiness.Blocked ->
                if (
                    observed.reason == WorkspaceReadinessReason.PROJECT_DISPOSED ||
                        observed.reason == WorkspaceReadinessReason.RETIRED_INCARNATION
                )
                    dispose()
            is WorkspaceCapabilityReadiness.Pending,
            is WorkspaceCapabilityReadiness.Unavailable -> Unit
        }
    }

    @Synchronized
    fun contains(id: WorkspaceRefreshRequestId): Boolean = entries.containsKey(WorkspaceRefreshWaiter.Explicit(id))

    @Synchronized
    fun hasWork(): Boolean =
        active != null || queue.isNotEmpty() || entries.values.any { it.status is WorkspaceRefreshStatus.Pending }

    @Synchronized
    fun dispose() {
        disposed = true
        queue.clear()
        entries.values.toList().forEach {
            if (it.status is WorkspaceRefreshStatus.Pending)
                transition(it, WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED))
        }
    }

    private fun makeRoom(): Boolean {
        while (entries.size >= capacity) {
            val terminal =
                entries.entries.firstOrNull { it.value.status !is WorkspaceRefreshStatus.Pending } ?: return false
            entries.remove(terminal.key)
        }
        return true
    }

    private fun expire() {
        val now = nanoTime()
        entries.values.toList().forEach {
            if (it.status is WorkspaceRefreshStatus.Pending && now - it.started >= pendingTimeoutNanos)
                transition(it, WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED))
        }
        pruneQueue()
        // The native attempt remains active even after every waiting deadline expires.
    }

    private fun pruneQueue() {
        queue.removeAll { attempt ->
            entries.values.none { it.attempt === attempt && it.status is WorkspaceRefreshStatus.Pending }
        }
    }

    private fun advance() {
        if (disposed || active != null || queue.isEmpty()) return
        val attempt = queue.removeFirst()
        active = attempt
        update(attempt, WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT))
        when (val effect = attempt.work.effect) {
            is WorkspaceRefreshEffectKind.Explicit -> port.start(effect.effect) { finish(attempt, it) }
            WorkspaceRefreshEffectKind.Incremental -> port.startIncremental { finish(attempt, it) }
        }
    }

    @Synchronized
    private fun finish(attempt: WorkspaceRefreshAttempt, result: WorkspaceRefreshEffectResult) {
        if (active !== attempt) return
        if (result == WorkspaceRefreshEffectResult.RETIRED) {
            dispose()
            return
        }
        if (disposed) {
            active = null
            return
        }
        expire()
        active = null
        val outcome = result.settledStatus(attempt.work.effect)
        update(attempt, outcome)
        if (result == WorkspaceRefreshEffectResult.DISPOSED) dispose()
        else {
            advance()
            observeAdmission()
        }
    }

    private fun update(attempt: WorkspaceRefreshAttempt, status: WorkspaceRefreshStatus) {
        entries.values.toList().forEach {
            if (it.attempt === attempt && it.status is WorkspaceRefreshStatus.Pending) transition(it, status)
        }
    }

    private fun transition(entry: WorkspaceRefreshEntry, status: WorkspaceRefreshStatus) {
        entry.status = status
        if (status !is WorkspaceRefreshStatus.Pending) entry.complete(status)
    }
}
