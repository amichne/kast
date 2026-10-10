package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness

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

/** Terminal results prove native settlement; RETIRED only retires model authority. Neither grants admission. */
internal interface WorkspaceRefreshPort {
    fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit)

    fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit)

    fun readiness(): WorkspaceCapabilityReadiness
}
