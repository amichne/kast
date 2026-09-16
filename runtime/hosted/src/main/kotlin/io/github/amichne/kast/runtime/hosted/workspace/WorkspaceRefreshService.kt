package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect

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
    CANCELLED,
    DISPOSED,
    DEADLINE_EXCEEDED,
}

internal enum class WorkspaceRefreshStage {
    QUEUED,
    EFFECT,
    ADMISSION,
}

internal enum class WorkspaceRefreshReadiness {
    READY,
    NOT_READY,
    DISPOSED,
}

internal enum class WorkspaceRefreshEffectResult {
    BUSY,
    UNSAVED_DOCUMENTS,
    UNLINKED_BUILD,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    DISPOSED,
}

internal sealed interface WorkspaceRefreshStatus {
    data class Pending(val stage: WorkspaceRefreshStage) : WorkspaceRefreshStatus

    data object Complete : WorkspaceRefreshStatus

    data class Failed(val reason: WorkspaceRefreshFailure) : WorkspaceRefreshStatus

    data class Rejected(val reason: WorkspaceRefreshRejection) : WorkspaceRefreshStatus
}

/** Bound to one authorized, already linked build. Neither callback may wait for IDE readiness. */
internal interface WorkspaceRefreshPort {
    fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit)

    fun readiness(): WorkspaceRefreshReadiness
}

/** Project-owned explicit lifecycle lane, independent of semantic request budgets. */
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

    private data class Work(val effect: WorkspaceRefreshEffect, val stamp: WorkspaceRefreshStamp)

    private data class Entry(val work: Work, val started: Long, var status: WorkspaceRefreshStatus)

    private val entries = linkedMapOf<WorkspaceRefreshRequestId, Entry>()
    private val queue = ArrayDeque<Work>()
    private var active: Work? = null
    private var disposed = false
    private var latestStamp = 0L

    @Synchronized
    fun submit(id: WorkspaceRefreshRequestId, effect: WorkspaceRefreshEffect): WorkspaceRefreshStatus {
        entries[id]?.let {
            return submit(id, effect, it.work.stamp)
        }
        if (latestStamp == Long.MAX_VALUE) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.CAPACITY)
        val stamp =
            when (val parsed = WorkspaceRefreshStamp.parse(latestStamp + 1)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return WorkspaceRefreshStatus.Rejected(parsed.failure)
            }
        return submit(id, effect, stamp)
    }

    @Synchronized
    fun submit(
        id: WorkspaceRefreshRequestId,
        effect: WorkspaceRefreshEffect,
        stamp: WorkspaceRefreshStamp,
    ): WorkspaceRefreshStatus {
        if (disposed) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.DISPOSED)
        val work = Work(effect, stamp)
        entries[id]?.let { previous ->
            if (previous.work != work)
                return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.REQUEST_CONFLICT)
            return status(id)
        }
        expire()
        if (entries.size >= capacity) return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.CAPACITY)
        latestStamp = maxOf(latestStamp, stamp.value)
        val equivalent = entries.values.firstOrNull { it.work == work && it.status is WorkspaceRefreshStatus.Pending }
        entries[id] =
            Entry(work, nanoTime(), equivalent?.status ?: WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.QUEUED))
        if (equivalent == null) queue.addLast(work)
        advance()
        return entries.getValue(id).status
    }

    @Synchronized
    fun status(id: WorkspaceRefreshRequestId): WorkspaceRefreshStatus {
        val entry = entries[id] ?: return WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.UNKNOWN_REQUEST)
        expire()
        advance()
        if (active != null || queue.isNotEmpty()) return entry.status
        if (entries.values.none { it.status is WorkspaceRefreshStatus.Pending }) return entry.status
        when (port.readiness()) {
            WorkspaceRefreshReadiness.READY -> completeAdmitted()
            WorkspaceRefreshReadiness.NOT_READY -> Unit
            WorkspaceRefreshReadiness.DISPOSED -> dispose()
        }
        return entry.status
    }

    private fun completeAdmitted() {
        entries.values.forEach { entry ->
            if (entry.status == WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION)) {
                entry.status = WorkspaceRefreshStatus.Complete
            }
        }
    }

    @Synchronized fun contains(id: WorkspaceRefreshRequestId): Boolean = entries.containsKey(id)

    @Synchronized
    fun hasWork(): Boolean =
        active != null || queue.isNotEmpty() || entries.values.any { it.status is WorkspaceRefreshStatus.Pending }

    @Synchronized
    fun dispose() {
        disposed = true
        queue.clear()
        entries.values.forEach {
            if (it.status is WorkspaceRefreshStatus.Pending)
                it.status = WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED)
        }
    }

    private fun expire() {
        val now = nanoTime()
        entries.values.forEach {
            if (it.status is WorkspaceRefreshStatus.Pending && now - it.started >= pendingTimeoutNanos) {
                it.status = WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED)
            }
        }
        queue.removeAll { work ->
            entries.values.none { it.work == work && it.status is WorkspaceRefreshStatus.Pending }
        }
    }

    private fun advance() {
        if (disposed || active != null || queue.isEmpty()) return
        val work = queue.removeFirst()
        active = work
        update(work, WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT))
        port.start(work.effect) { result -> finish(work, result) }
    }

    @Synchronized
    private fun finish(work: Work, result: WorkspaceRefreshEffectResult) {
        if (disposed || active != work) return
        expire()
        active = null
        val outcome =
            when (result) {
                WorkspaceRefreshEffectResult.BUSY -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.BUSY)
                WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS ->
                    WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.UNSAVED_DOCUMENTS)
                WorkspaceRefreshEffectResult.UNLINKED_BUILD ->
                    WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.UNLINKED_BUILD)
                WorkspaceRefreshEffectResult.SUCCEEDED ->
                    WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION)
                WorkspaceRefreshEffectResult.FAILED ->
                    WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.EFFECT_FAILED)
                WorkspaceRefreshEffectResult.CANCELLED ->
                    WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED)
                WorkspaceRefreshEffectResult.DISPOSED -> WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED)
            }
        update(work, outcome)
        if (result == WorkspaceRefreshEffectResult.DISPOSED) dispose() else advance()
    }

    private fun update(work: Work, status: WorkspaceRefreshStatus) {
        entries.values.forEach {
            if (it.work == work && it.status is WorkspaceRefreshStatus.Pending) it.status = status
        }
    }
}
