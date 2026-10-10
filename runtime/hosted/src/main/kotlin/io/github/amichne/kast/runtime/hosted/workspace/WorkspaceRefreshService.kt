package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason

@JvmInline
internal value class WorkspaceRefreshRequestId private constructor(val value: String) {
    companion object {
        private const val MAX_REQUEST_ID_CHARACTERS = 128

        fun parse(value: String): Refinement<WorkspaceRefreshRequestId, WorkspaceRefreshRejection> =
            if (value.isNotBlank() && value.length <= MAX_REQUEST_ID_CHARACTERS)
                Refinement.Refined(WorkspaceRefreshRequestId(value))
            else Refinement.Rejected(WorkspaceRefreshRejection.INVALID_REQUEST)
    }
}

@JvmInline
internal value class WorkspaceRefreshStamp private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<WorkspaceRefreshStamp, WorkspaceRefreshRejection> =
            if (value >= 0) Refinement.Refined(WorkspaceRefreshStamp(value))
            else Refinement.Rejected(WorkspaceRefreshRejection.INVALID_REQUEST)
    }
}

internal enum class WorkspaceRefreshRejection {
    INVALID_REQUEST,
    REQUEST_CONFLICT,
    CAPACITY,
    UNKNOWN_REQUEST,
    DISPOSED,
}

internal enum class WorkspaceRefreshFailure {
    BUSY,
    UNSAVED_DOCUMENTS,
    UNLINKED_BUILD,
    EFFECT_FAILED,
    ROOT_UNAVAILABLE,
    CANCELLED,
    DISPOSED,
    DEADLINE_EXCEEDED,
}

internal enum class WorkspaceRefreshStage {
    QUEUED,
    EFFECT,
    ADMISSION,
}

@kotlinx.serialization.Serializable
internal enum class WorkspaceRefreshEffectResult {
    BUSY,
    UNSAVED_DOCUMENTS,
    UNLINKED_BUILD,
    ROOT_UNAVAILABLE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    DISPOSED,
    RETIRED,
}

internal sealed interface WorkspaceRefreshStatus {
    data class Pending(val stage: WorkspaceRefreshStage) : WorkspaceRefreshStatus

    data object Complete : WorkspaceRefreshStatus

    data class Failed(val reason: WorkspaceRefreshFailure) : WorkspaceRefreshStatus

    data class Rejected(val reason: WorkspaceRefreshRejection) : WorkspaceRefreshStatus
}

@JvmInline
internal value class WorkspaceRefreshAttemptId private constructor(val value: Long) {
    companion object {
        fun fromSequence(sequence: Long): WorkspaceRefreshAttemptId {
            require(sequence > 0)
            return WorkspaceRefreshAttemptId(sequence)
        }
    }
}

internal enum class WorkspaceRefreshNativeEffect {
    INCREMENTAL_FILES,
    FORCED_FILES,
    MODEL_RELOAD,
}

internal sealed interface WorkspaceRefreshWaiterIdentity {
    data class Request(val id: WorkspaceRefreshRequestId) : WorkspaceRefreshWaiterIdentity
    data object ReadPreparation : WorkspaceRefreshWaiterIdentity
}

internal data class WorkspaceRefreshWaiterInspection(
    val identity: WorkspaceRefreshWaiterIdentity,
    val status: WorkspaceRefreshStatus,
)

internal data class WorkspaceRefreshAttemptInspection(
    val id: WorkspaceRefreshAttemptId,
    val effect: WorkspaceRefreshNativeEffect,
    val stamp: WorkspaceRefreshStamp,
    val initiator: WorkspaceRefreshWaiterIdentity,
    val waiters: List<WorkspaceRefreshWaiterInspection>,
)

internal sealed interface WorkspaceRefreshInspection {
    data object Idle : WorkspaceRefreshInspection
    data class Retired(val unsettled: List<WorkspaceRefreshAttemptInspection>) : WorkspaceRefreshInspection
    data class Running(
        val active: WorkspaceRefreshAttemptInspection,
        val queued: List<WorkspaceRefreshAttemptInspection>,
    ) : WorkspaceRefreshInspection
    data class AwaitingAdmission(val waiters: List<WorkspaceRefreshWaiterInspection>) : WorkspaceRefreshInspection
}

/** Terminal results prove native settlement; RETIRED only retires model authority. Neither grants admission. */
internal interface WorkspaceRefreshPort {
    fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit)

    fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit)

    fun readiness(): WorkspaceCapabilityReadiness
}

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

    private sealed interface Effect {
        data class Explicit(val effect: WorkspaceRefreshEffect) : Effect
        data object Incremental : Effect
    }

    private data class Work(val effect: Effect, val stamp: WorkspaceRefreshStamp)

    // Referential identity prevents a duplicate native callback from settling an identical later retry.
    private class Attempt(
        val work: Work,
        val id: WorkspaceRefreshAttemptId,
        val initiator: WorkspaceRefreshWaiterIdentity,
    )

    private sealed interface Waiter {
        data class Explicit(val id: WorkspaceRefreshRequestId) : Waiter
        class Read : Waiter
    }

    private class Entry(
        val attempt: Attempt,
        val started: Long,
        var status: WorkspaceRefreshStatus,
        val complete: (WorkspaceRefreshStatus) -> Unit = {},
    )

    private val entries = linkedMapOf<Waiter, Entry>()
    private val queue = ArrayDeque<Attempt>()
    private var active: Attempt? = null
    private var disposed = false
    private var latestStamp = 0L
    private var latestAttempt = 0L

    @Synchronized
    fun submit(id: WorkspaceRefreshRequestId, effect: WorkspaceRefreshEffect): WorkspaceRefreshStatus {
        entries[Waiter.Explicit(id)]?.let { return submit(id, effect, it.attempt.work.stamp) }
        return when (val stamp = nextStamp()) {
            is Refinement.Refined -> submit(id, effect, stamp.value)
            is Refinement.Rejected -> WorkspaceRefreshStatus.Rejected(stamp.failure)
        }
    }

    @Synchronized
    fun submit(
        id: WorkspaceRefreshRequestId,
        effect: WorkspaceRefreshEffect,
        stamp: WorkspaceRefreshStamp,
    ): WorkspaceRefreshStatus {
        if (disposed) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.DISPOSED)
        val work = Work(Effect.Explicit(effect), stamp)
        val waiter = Waiter.Explicit(id)
        entries[waiter]?.let { previous ->
            if (previous.attempt.work != work)
                return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.REQUEST_CONFLICT)
            return status(id)
        }
        expire()
        observeAdmission()
        if (!makeRoom()) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.CAPACITY)
        latestStamp = maxOf(latestStamp, stamp.value)
        when (val added = add(waiter, work)) {
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
        val equivalent = entries.values.firstOrNull {
            it.attempt.work.effect == Effect.Incremental && it.status is WorkspaceRefreshStatus.Pending
        }
        val work = equivalent?.attempt?.work ?: when (val stamp = nextStamp()) {
            is Refinement.Refined -> Work(Effect.Incremental, stamp.value)
            is Refinement.Rejected -> {
                complete(WorkspaceRefreshStatus.Rejected(stamp.failure))
                return {}
            }
        }
        val waiter = Waiter.Read()
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
        waiter: Waiter,
        work: Work,
        complete: (WorkspaceRefreshStatus) -> Unit = {},
    ): Refinement<Unit, WorkspaceRefreshRejection> {
        val equivalent = entries.values.firstOrNull {
            it.attempt.work == work && it.status is WorkspaceRefreshStatus.Pending
        }
        val attempt = equivalent?.attempt ?: run {
            if (latestAttempt == Long.MAX_VALUE) return Refinement.Rejected(WorkspaceRefreshRejection.CAPACITY)
            Attempt(work, WorkspaceRefreshAttemptId.fromSequence(++latestAttempt), waiter.identity()).also(queue::addLast)
        }
        entries[waiter] = Entry(
            attempt,
            nanoTime(),
            equivalent?.status ?: WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.QUEUED),
            complete,
        )
        return Refinement.Refined(Unit)
    }

    private fun Waiter.identity(): WorkspaceRefreshWaiterIdentity = when (this) {
        is Waiter.Explicit -> WorkspaceRefreshWaiterIdentity.Request(id)
        is Waiter.Read -> WorkspaceRefreshWaiterIdentity.ReadPreparation
    }

    @Synchronized
    fun inspection(): WorkspaceRefreshInspection {
        expire()
        if (disposed) return WorkspaceRefreshInspection.Retired(listOfNotNull(active?.inspection()))
        active?.let { return WorkspaceRefreshInspection.Running(it.inspection(), queue.map { queued -> queued.inspection() }) }
        val admission = entries.filterValues { it.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION) }
        if (admission.isNotEmpty()) return WorkspaceRefreshInspection.AwaitingAdmission(
            admission.map { (waiter, entry) -> WorkspaceRefreshWaiterInspection(waiter.identity(), entry.status) }
        )
        return WorkspaceRefreshInspection.Idle
    }

    private fun Attempt.inspection(): WorkspaceRefreshAttemptInspection = WorkspaceRefreshAttemptInspection(
        id,
        when (val effect = work.effect) {
            Effect.Incremental -> WorkspaceRefreshNativeEffect.INCREMENTAL_FILES
            is Effect.Explicit -> when (effect.effect) {
                WorkspaceRefreshEffect.FILE_REFRESH -> WorkspaceRefreshNativeEffect.FORCED_FILES
                WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD -> WorkspaceRefreshNativeEffect.MODEL_RELOAD
            }
        },
        work.stamp,
        initiator,
        entries.filterValues { it.attempt === this }.map { (waiter, entry) ->
            WorkspaceRefreshWaiterInspection(waiter.identity(), entry.status)
        },
    )

    @Synchronized
    private fun cancel(waiter: Waiter) {
        entries[waiter]?.let { entry ->
            if (entry.status is WorkspaceRefreshStatus.Pending)
                transition(entry, WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED))
        }
        pruneQueue()
    }

    @Synchronized
    fun status(id: WorkspaceRefreshRequestId): WorkspaceRefreshStatus {
        val entry = entries[Waiter.Explicit(id)]
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
            is WorkspaceCapabilityReadiness.Ready -> entries.values.toList().forEach {
                if (it.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION))
                    transition(it, WorkspaceRefreshStatus.Complete)
            }
            is WorkspaceCapabilityReadiness.Blocked ->
                if (
                    observed.reason == WorkspaceReadinessReason.PROJECT_DISPOSED ||
                    observed.reason == WorkspaceReadinessReason.RETIRED_INCARNATION
                ) dispose()
            is WorkspaceCapabilityReadiness.Pending,
            is WorkspaceCapabilityReadiness.Unavailable -> Unit
        }
    }

    @Synchronized fun contains(id: WorkspaceRefreshRequestId): Boolean = entries.containsKey(Waiter.Explicit(id))

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
            val terminal = entries.entries.firstOrNull { it.value.status !is WorkspaceRefreshStatus.Pending }
                ?: return false
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
            is Effect.Explicit -> port.start(effect.effect) { finish(attempt, it) }
            Effect.Incremental -> port.startIncremental { finish(attempt, it) }
        }
    }

    @Synchronized
    private fun finish(attempt: Attempt, result: WorkspaceRefreshEffectResult) {
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
        val outcome = when (result) {
            WorkspaceRefreshEffectResult.BUSY -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.BUSY)
            WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS ->
                WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.UNSAVED_DOCUMENTS)
            WorkspaceRefreshEffectResult.UNLINKED_BUILD ->
                WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.UNLINKED_BUILD)
            WorkspaceRefreshEffectResult.ROOT_UNAVAILABLE ->
                WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.ROOT_UNAVAILABLE)
            WorkspaceRefreshEffectResult.SUCCEEDED ->
                if (attempt.work.effect == Effect.Incremental) WorkspaceRefreshStatus.Complete
                else WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION)
            WorkspaceRefreshEffectResult.FAILED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.EFFECT_FAILED)
            WorkspaceRefreshEffectResult.CANCELLED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED)
            WorkspaceRefreshEffectResult.DISPOSED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED)
            WorkspaceRefreshEffectResult.RETIRED -> error("Retirement is handled before terminal settlement")
        }
        update(attempt, outcome)
        if (result == WorkspaceRefreshEffectResult.DISPOSED) dispose()
        else {
            advance()
            observeAdmission()
        }
    }

    private fun update(attempt: Attempt, status: WorkspaceRefreshStatus) {
        entries.values.toList().forEach {
            if (it.attempt === attempt && it.status is WorkspaceRefreshStatus.Pending) transition(it, status)
        }
    }

    private fun transition(entry: Entry, status: WorkspaceRefreshStatus) {
        entry.status = status
        if (status !is WorkspaceRefreshStatus.Pending) entry.complete(status)
    }
}
