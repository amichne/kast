package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.kernel.Refinement

enum class LongOperationPositiveLimitFailure {
    NOT_POSITIVE,
}

enum class LongOperationDurationFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

@JvmInline
value class LongOperationCapacity private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `Int -> Refinement<LongOperationCapacity, LongOperationPositiveLimitFailure>`.
         *
         * Establishes a strictly positive bound on simultaneously registered operations.
         * [LongOperationPositiveLimitFailure] is the closed expected failure. Raw extraction is
         * permitted only inside registry capacity admission.
         */
        fun parse(
            raw: Int,
        ): Refinement<LongOperationCapacity, LongOperationPositiveLimitFailure> =
            if (raw > 0) {
                Refinement.Refined(LongOperationCapacity(raw))
            } else {
                Refinement.Rejected(LongOperationPositiveLimitFailure.NOT_POSITIVE)
            }
    }
}

@JvmInline
value class LongOperationDeadlineMillis private constructor(
    val value: Long,
) {
    internal fun delay(): LongOperationDelay =
        LongOperationDelay(value * NANOS_PER_MILLISECOND)

    companion object {
        /**
         * Proof transition:
         * `Long -> Refinement<LongOperationDeadlineMillis, LongOperationDurationFailure>`.
         *
         * Establishes a positive server-owned deadline whose nanosecond delay cannot overflow.
         * [LongOperationDurationFailure] is the closed expected failure. Raw extraction is permitted
         * only at policy configuration.
         */
        fun parse(
            raw: Long,
        ): Refinement<LongOperationDeadlineMillis, LongOperationDurationFailure> =
            duration(raw, ::LongOperationDeadlineMillis)
    }
}

@JvmInline
value class LongOperationRetentionMillis private constructor(
    val value: Long,
) {
    internal fun delay(): LongOperationDelay =
        LongOperationDelay(value * NANOS_PER_MILLISECOND)

    companion object {
        /**
         * Proof transition:
         * `Long -> Refinement<LongOperationRetentionMillis, LongOperationDurationFailure>`.
         *
         * Establishes a positive terminal-retention duration whose nanosecond delay cannot overflow.
         * [LongOperationDurationFailure] is the closed expected failure. Raw extraction is permitted
         * only at policy configuration.
         */
        fun parse(
            raw: Long,
        ): Refinement<LongOperationRetentionMillis, LongOperationDurationFailure> =
            duration(raw, ::LongOperationRetentionMillis)
    }
}

@JvmInline
value class LongOperationDelay internal constructor(
    val nanoseconds: Long,
)

data class LongOperationPolicy(
    val capacity: LongOperationCapacity,
    val deadline: LongOperationDeadlineMillis,
    val terminalRetention: LongOperationRetentionMillis,
)

fun interface LongOperationScheduledSignal {
    /** Delivers one store-owned deadline or retention signal without operation work or live state. */
    fun fire()
}

fun interface LongOperationScheduledTask {
    /** Cancels this armed signal idempotently. */
    fun cancel()
}

sealed interface LongOperationScheduleResult {
    data class Armed(
        val task: LongOperationScheduledTask,
    ) : LongOperationScheduleResult

    data object Rejected : LongOperationScheduleResult
}

fun interface LongOperationScheduler {
    /**
     * Proof transition: `LongOperationDelay + LongOperationScheduledSignal -> LongOperationScheduleResult`.
     *
     * Establishes that an admitted signal cannot run inline or before the supplied monotonic delay.
     * Rejection is finite data. The raw nanosecond value may be extracted only by the scheduler.
     */
    fun arm(
        delay: LongOperationDelay,
        signal: LongOperationScheduledSignal,
    ): LongOperationScheduleResult
}

enum class LongOperationCancellationPolicy {
    UNSUPPORTED,
    SUPPORTED,
}

enum class LongOperationTerminalFailure {
    DEADLINE_EXCEEDED,
    CANCELLED,
    EXECUTION_FAILED,
}

sealed interface LongOperationCompletion {
    data class Succeeded(
        val output: DetachedContinuationRecord,
    ) : LongOperationCompletion

    data object Failed : LongOperationCompletion
}

sealed interface LongOperationTerminalResult {
    data class Succeeded(
        val output: DetachedContinuationRecord,
    ) : LongOperationTerminalResult

    data class Failed(
        val failure: LongOperationTerminalFailure,
    ) : LongOperationTerminalResult
}

sealed interface LongOperationState {
    data object Running : LongOperationState

    data class Terminal(
        val result: LongOperationTerminalResult,
    ) : LongOperationState
}

enum class LongOperationStartFailure {
    STORE_CLOSED,
    CAPACITY_REACHED,
    ID_COLLISION,
    ID_ISSUER_FAILURE,
    DEADLINE_SCHEDULER_REJECTED,
}

sealed interface LongOperationStartResult {
    data class Started(
        val operationId: LongOperationId,
    ) : LongOperationStartResult

    data class Rejected(
        val failure: LongOperationStartFailure,
    ) : LongOperationStartResult
}

enum class LongOperationAccessFailure {
    STORE_CLOSED,
    UNKNOWN_OPERATION,
    EXPIRED,
    WRONG_WORKSPACE_ROOT,
    REQUESTER_CHANGED,
    RUNTIME_EPOCH_CHANGED,
    CAPABILITY_CHANGED,
    INPUT_CHANGED,
    ALREADY_TERMINAL,
    DEADLINE_EXCEEDED,
    CANCELLATION_UNSUPPORTED,
    RETENTION_SCHEDULER_REJECTED,
}

sealed interface LongOperationPollResult {
    data class Observed(
        val state: LongOperationState,
    ) : LongOperationPollResult

    data class Rejected(
        val failure: LongOperationAccessFailure,
    ) : LongOperationPollResult
}

sealed interface LongOperationCompletionResult {
    data object Completed : LongOperationCompletionResult

    data class Rejected(
        val failure: LongOperationAccessFailure,
    ) : LongOperationCompletionResult
}

sealed interface LongOperationCancellationResult {
    data object Cancelled : LongOperationCancellationResult

    data class Rejected(
        val failure: LongOperationAccessFailure,
    ) : LongOperationCancellationResult
}

internal class RegisteredLongOperationKey

internal data class RegisteredLongOperation(
    val key: RegisteredLongOperationKey,
    val binding: LongOperationBinding,
    val cancellationPolicy: LongOperationCancellationPolicy,
    val phase: LongOperationPhase,
)

internal sealed interface LongOperationPhase {
    data class Running(
        val startedAt: LongOperationObservedAtNanos,
        val deadlineTask: LongOperationScheduledTask,
    ) : LongOperationPhase

    data class Terminal(
        val result: LongOperationTerminalResult,
        val terminalAt: LongOperationObservedAtNanos,
        val retentionTask: LongOperationScheduledTask,
    ) : LongOperationPhase
}

@JvmInline
internal value class LongOperationObservedAtNanos(
    val value: Long,
)

internal enum class LongOperationStoreLifecycle {
    OPEN,
    CLOSED,
}

internal sealed interface LongOperationIdentityPublication {
    data class Published(
        val operationId: LongOperationId,
    ) : LongOperationIdentityPublication

    data class Rejected(
        val failure: LongOperationStartFailure,
    ) : LongOperationIdentityPublication
}

internal sealed interface LongOperationBindingAdmission {
    data object Admitted : LongOperationBindingAdmission

    data class Rejected(
        val failure: LongOperationAccessFailure,
    ) : LongOperationBindingAdmission
}

internal sealed interface LongOperationRefresh {
    data class Retained(
        val entry: RegisteredLongOperation,
    ) : LongOperationRefresh

    data class Rejected(
        val failure: LongOperationAccessFailure,
    ) : LongOperationRefresh
}

internal fun admitBinding(
    stored: LongOperationBinding,
    presented: LongOperationBinding,
): LongOperationBindingAdmission = when {
    stored.workspaceRoot != presented.workspaceRoot ->
        LongOperationBindingAdmission.Rejected(LongOperationAccessFailure.WRONG_WORKSPACE_ROOT)
    stored.requester != presented.requester ->
        LongOperationBindingAdmission.Rejected(LongOperationAccessFailure.REQUESTER_CHANGED)
    stored.runtimeEpoch != presented.runtimeEpoch ->
        LongOperationBindingAdmission.Rejected(LongOperationAccessFailure.RUNTIME_EPOCH_CHANGED)
    stored.declaredCapability != presented.declaredCapability ->
        LongOperationBindingAdmission.Rejected(LongOperationAccessFailure.CAPABILITY_CHANGED)
    stored.inputIdentity != presented.inputIdentity ->
        LongOperationBindingAdmission.Rejected(LongOperationAccessFailure.INPUT_CHANGED)
    else -> LongOperationBindingAdmission.Admitted
}

internal fun LongOperationPhase.publicState(): LongOperationState = when (this) {
    is LongOperationPhase.Running -> LongOperationState.Running
    is LongOperationPhase.Terminal -> LongOperationState.Terminal(result)
}

internal fun LongOperationPhase.Terminal.completionFailure(): LongOperationAccessFailure =
    when (val terminal = result) {
        is LongOperationTerminalResult.Failed ->
            if (terminal.failure == LongOperationTerminalFailure.DEADLINE_EXCEEDED) {
                LongOperationAccessFailure.DEADLINE_EXCEEDED
            } else {
                LongOperationAccessFailure.ALREADY_TERMINAL
            }
        is LongOperationTerminalResult.Succeeded -> LongOperationAccessFailure.ALREADY_TERMINAL
    }

internal fun startRejected(
    failure: LongOperationStartFailure,
): LongOperationStartResult.Rejected = LongOperationStartResult.Rejected(failure)

internal fun pollRejected(
    failure: LongOperationAccessFailure,
): LongOperationPollResult.Rejected = LongOperationPollResult.Rejected(failure)

internal fun completionRejected(
    failure: LongOperationAccessFailure,
): LongOperationCompletionResult.Rejected = LongOperationCompletionResult.Rejected(failure)

internal fun cancellationRejected(
    failure: LongOperationAccessFailure,
): LongOperationCancellationResult.Rejected = LongOperationCancellationResult.Rejected(failure)

/** Refines a raw millisecond duration into one overflow-safe operation duration. */
private fun <Strong> duration(
    raw: Long,
    create: (Long) -> Strong,
): Refinement<Strong, LongOperationDurationFailure> = when {
    raw <= 0L -> Refinement.Rejected(LongOperationDurationFailure.NOT_POSITIVE)
    raw > Long.MAX_VALUE / NANOS_PER_MILLISECOND ->
        Refinement.Rejected(LongOperationDurationFailure.TOO_LARGE)
    else -> Refinement.Refined(create(raw))
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
