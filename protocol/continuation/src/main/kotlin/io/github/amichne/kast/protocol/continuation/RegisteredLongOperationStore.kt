package io.github.amichne.kast.protocol.continuation

/**
 * Bounded host-neutral registry for work that must outlive one transport request budget.
 *
 * The registry owns identity, binding, deadline, observation, terminal replay, retention, and
 * timer cleanup. The operation executor remains outside this module and completes by ID.
 */
class RegisteredLongOperationStore(
    private val policy: LongOperationPolicy,
    private val idIssuer: LongOperationIdIssuer = LongOperationIdIssuer.Random,
    private val clock: ContinuationClock = ContinuationClock.System,
    private val scheduler: LongOperationScheduler,
) : AutoCloseable {
    private val lock = Any()
    private val entries = linkedMapOf<LongOperationId, RegisteredLongOperation>()
    private var lifecycle = LongOperationStoreLifecycle.OPEN

    /**
     * Proof transition:
     * `LongOperationBinding + LongOperationCancellationPolicy -> LongOperationStartResult`.
     *
     * Establishes a capacity-admitted unique operation whose exact binding and non-renewing
     * server-owned deadline are armed before [LongOperationId] publication. [LongOperationStartFailure]
     * is the closed expected failure. The issuer, scheduler, clock, and raw map are confined here.
     */
    fun start(
        binding: LongOperationBinding,
        cancellationPolicy: LongOperationCancellationPolicy =
            LongOperationCancellationPolicy.UNSUPPORTED,
    ): LongOperationStartResult = synchronized(lock) {
        if (lifecycle == LongOperationStoreLifecycle.CLOSED) {
            return@synchronized startRejected(LongOperationStartFailure.STORE_CLOSED)
        }
        if (entries.size >= policy.capacity.value) {
            return@synchronized startRejected(LongOperationStartFailure.CAPACITY_REACHED)
        }
        val operationId = when (val issuance = issueIdentity()) {
            is LongOperationIdentityPublication.Published -> issuance.operationId
            is LongOperationIdentityPublication.Rejected ->
                return@synchronized startRejected(issuance.failure)
        }
        if (operationId in entries) {
            return@synchronized startRejected(LongOperationStartFailure.ID_COLLISION)
        }
        val key = RegisteredLongOperationKey()
        val startedAt = LongOperationObservedAtNanos(clock.nowNanos())
        val deadline = arm(policy.deadline.delay()) {
            refreshFromTimer(operationId, key)
        }
        val deadlineTask = when (deadline) {
            is LongOperationScheduleResult.Armed -> deadline.task
            LongOperationScheduleResult.Rejected ->
                return@synchronized startRejected(
                    LongOperationStartFailure.DEADLINE_SCHEDULER_REJECTED,
                )
        }
        entries[operationId] = RegisteredLongOperation(
            key = key,
            binding = binding,
            cancellationPolicy = cancellationPolicy,
            phase = LongOperationPhase.Running(startedAt, deadlineTask),
        )
        LongOperationStartResult.Started(operationId)
    }

    /**
     * Proof transition:
     * `LongOperationId + LongOperationBinding -> LongOperationPollResult`.
     *
     * Establishes exact binding admission and a detached running or replayable terminal state.
     * [LongOperationAccessFailure] is the closed expected failure. Polling never schedules, renews,
     * or otherwise extends the operation deadline or terminal-retention interval.
     */
    fun poll(
        operationId: LongOperationId,
        binding: LongOperationBinding,
    ): LongOperationPollResult = synchronized(lock) {
        val entry = when (val admission = admit(operationId, binding)) {
            is LongOperationEntryAdmission.Admitted -> admission.entry
            is LongOperationEntryAdmission.Rejected ->
                return@synchronized pollRejected(admission.failure)
        }
        when (val refreshed = refresh(operationId, entry)) {
            is LongOperationRefresh.Retained ->
                LongOperationPollResult.Observed(refreshed.entry.phase.publicState())
            is LongOperationRefresh.Rejected -> pollRejected(refreshed.failure)
        }
    }

    /**
     * Proof transition:
     * `LongOperationId + LongOperationBinding + LongOperationCompletion -> LongOperationCompletionResult`.
     *
     * Establishes that one admitted running operation owns a detached terminal success or the typed
     * execution failure under a fixed retention timer. [LongOperationAccessFailure] is the closed
     * expected failure. Raw completion production is permitted only in the external worker.
     */
    fun complete(
        operationId: LongOperationId,
        binding: LongOperationBinding,
        completion: LongOperationCompletion,
    ): LongOperationCompletionResult = synchronized(lock) {
        val entry = when (val admission = admit(operationId, binding)) {
            is LongOperationEntryAdmission.Admitted -> admission.entry
            is LongOperationEntryAdmission.Rejected ->
                return@synchronized completionRejected(admission.failure)
        }
        when (val refreshed = refresh(operationId, entry)) {
            is LongOperationRefresh.Rejected ->
                return@synchronized completionRejected(refreshed.failure)
            is LongOperationRefresh.Retained -> {
                if (refreshed.entry.phase is LongOperationPhase.Terminal) {
                    return@synchronized completionRejected(
                        refreshed.entry.phase.completionFailure(),
                    )
                }
                val result = when (completion) {
                    is LongOperationCompletion.Succeeded ->
                        LongOperationTerminalResult.Succeeded(completion.output)
                    LongOperationCompletion.Failed ->
                        LongOperationTerminalResult.Failed(
                            LongOperationTerminalFailure.EXECUTION_FAILED,
                        )
                }
                when (val terminal = retainTerminal(operationId, refreshed.entry, result)) {
                    is LongOperationRefresh.Retained -> LongOperationCompletionResult.Completed
                    is LongOperationRefresh.Rejected -> completionRejected(terminal.failure)
                }
            }
        }
    }

    /**
     * Proof transition:
     * `LongOperationId + LongOperationBinding -> LongOperationCancellationResult`.
     *
     * Establishes exact binding and declared cancellation admission before recording the typed,
     * replayable cancellation terminal. Unsupported cancellation is
     * [LongOperationAccessFailure.CANCELLATION_UNSUPPORTED].
     */
    fun cancel(
        operationId: LongOperationId,
        binding: LongOperationBinding,
    ): LongOperationCancellationResult = synchronized(lock) {
        val entry = when (val admission = admit(operationId, binding)) {
            is LongOperationEntryAdmission.Admitted -> admission.entry
            is LongOperationEntryAdmission.Rejected ->
                return@synchronized cancellationRejected(admission.failure)
        }
        when (val refreshed = refresh(operationId, entry)) {
            is LongOperationRefresh.Rejected ->
                return@synchronized cancellationRejected(refreshed.failure)
            is LongOperationRefresh.Retained -> {
                val current = refreshed.entry
                if (current.phase is LongOperationPhase.Terminal) {
                    return@synchronized cancellationRejected(
                        current.phase.completionFailure(),
                    )
                }
                if (current.cancellationPolicy == LongOperationCancellationPolicy.UNSUPPORTED) {
                    return@synchronized cancellationRejected(
                        LongOperationAccessFailure.CANCELLATION_UNSUPPORTED,
                    )
                }
                val result = LongOperationTerminalResult.Failed(
                    LongOperationTerminalFailure.CANCELLED,
                )
                when (val terminal = retainTerminal(operationId, current, result)) {
                    is LongOperationRefresh.Retained ->
                        LongOperationCancellationResult.Cancelled
                    is LongOperationRefresh.Rejected ->
                        cancellationRejected(terminal.failure)
                }
            }
        }
    }

    /** Cancels every active timer, releases every entry once, and permanently closes the registry. */
    override fun close() = synchronized(lock) {
        if (lifecycle == LongOperationStoreLifecycle.CLOSED) {
            return@synchronized
        }
        lifecycle = LongOperationStoreLifecycle.CLOSED
        val owned = entries.values.toList()
        entries.clear()
        owned.forEach(::cancelActiveTimer)
    }

    private fun issueIdentity(): LongOperationIdentityPublication {
        val operationId = try {
            idIssuer.issue()
        } catch (_: RuntimeException) {
            return LongOperationIdentityPublication.Rejected(
                LongOperationStartFailure.ID_ISSUER_FAILURE,
            )
        }
        return LongOperationIdentityPublication.Published(operationId)
    }

    /**
     * Proof transition:
     * `LongOperationId + LongOperationBinding -> LongOperationEntryAdmission`.
     *
     * Establishes an open-store, known-operation entry under exact binding identity, or one closed
     * [LongOperationAccessFailure]. Raw registry access remains inside this store.
     */
    private fun admit(
        operationId: LongOperationId,
        binding: LongOperationBinding,
    ): LongOperationEntryAdmission {
        if (lifecycle == LongOperationStoreLifecycle.CLOSED) {
            return LongOperationEntryAdmission.Rejected(LongOperationAccessFailure.STORE_CLOSED)
        }
        val entry = entries[operationId]
                    ?: return LongOperationEntryAdmission.Rejected(
                        LongOperationAccessFailure.UNKNOWN_OPERATION,
                    )
        return when (val bindingAdmission = admitBinding(entry.binding, binding)) {
            LongOperationBindingAdmission.Admitted -> LongOperationEntryAdmission.Admitted(entry)
            is LongOperationBindingAdmission.Rejected ->
                LongOperationEntryAdmission.Rejected(bindingAdmission.failure)
        }
    }

    private fun refreshFromTimer(
        operationId: LongOperationId,
        key: RegisteredLongOperationKey,
    ) = synchronized(lock) {
        val entry = entries[operationId] ?: return@synchronized
        if (entry.key !== key) {
            return@synchronized
        }
        refresh(operationId, entry)
        Unit
    }

    private fun refresh(
        operationId: LongOperationId,
        entry: RegisteredLongOperation,
    ): LongOperationRefresh = when (val phase = entry.phase) {
        is LongOperationPhase.Running ->
            if (elapsedSince(phase.startedAt) >= policy.deadline.delay().nanoseconds) {
                retainTerminal(
                    operationId,
                    entry,
                    LongOperationTerminalResult.Failed(
                        LongOperationTerminalFailure.DEADLINE_EXCEEDED,
                    ),
                )
            } else {
                LongOperationRefresh.Retained(entry)
            }
        is LongOperationPhase.Terminal ->
            if (elapsedSince(phase.terminalAt) >= policy.terminalRetention.delay().nanoseconds) {
                release(operationId, entry)
                LongOperationRefresh.Rejected(LongOperationAccessFailure.EXPIRED)
            } else {
                LongOperationRefresh.Retained(entry)
            }
    }

    private fun retainTerminal(
        operationId: LongOperationId,
        entry: RegisteredLongOperation,
        result: LongOperationTerminalResult,
    ): LongOperationRefresh {
        val running = entry.phase as? LongOperationPhase.Running
                      ?: return LongOperationRefresh.Retained(entry)
        val terminalAt = LongOperationObservedAtNanos(clock.nowNanos())
        val retention = arm(policy.terminalRetention.delay()) {
            refreshFromTimer(operationId, entry.key)
        }
        val retentionTask = when (retention) {
            is LongOperationScheduleResult.Armed -> retention.task
            LongOperationScheduleResult.Rejected -> {
                release(operationId, entry)
                return LongOperationRefresh.Rejected(
                    LongOperationAccessFailure.RETENTION_SCHEDULER_REJECTED,
                )
            }
        }
        cancel(running.deadlineTask)
        val terminal = entry.copy(
            phase = LongOperationPhase.Terminal(result, terminalAt, retentionTask),
        )
        entries[operationId] = terminal
        return LongOperationRefresh.Retained(terminal)
    }

    private fun release(
        operationId: LongOperationId,
        expected: RegisteredLongOperation,
    ) {
        val current = entries[operationId] ?: return
        if (current.key !== expected.key) {
            return
        }
        entries.remove(operationId)
        cancelActiveTimer(current)
    }

    private fun cancelActiveTimer(entry: RegisteredLongOperation) {
        when (val phase = entry.phase) {
            is LongOperationPhase.Running -> cancel(phase.deadlineTask)
            is LongOperationPhase.Terminal -> cancel(phase.retentionTask)
        }
    }

    private fun cancel(task: LongOperationScheduledTask) {
        try {
            task.cancel()
        } catch (_: RuntimeException) {
            // State ownership is already released; timer cancellation is best-effort and idempotent.
        }
    }

    private fun arm(
        delay: LongOperationDelay,
        signal: LongOperationScheduledSignal,
    ): LongOperationScheduleResult = try {
        scheduler.arm(delay, signal)
    } catch (_: RuntimeException) {
        LongOperationScheduleResult.Rejected
    }

    private fun elapsedSince(observed: LongOperationObservedAtNanos): Long =
        (clock.nowNanos() - observed.value).coerceAtLeast(0L)
}

private sealed interface LongOperationEntryAdmission {
    data class Admitted(
        val entry: RegisteredLongOperation,
    ) : LongOperationEntryAdmission

    data class Rejected(
        val failure: LongOperationAccessFailure,
    ) : LongOperationEntryAdmission
}
