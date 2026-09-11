package io.github.amichne.kast.fixtureprobe

import kotlinx.serialization.Serializable

internal enum class ProbeImportState {
    NOT_OBSERVED,
    IMPORTING,
    IMPORT_FINISHED,
    FINALIZING,
    FINAL_TASKS_FINISHED,
    FAILED,
}

@Serializable
internal enum class ProbeSetupImportEvidence {
    FINAL_TASKS_OBSERVED,
    NOT_OBSERVED_PERSISTED_MODEL,
}

internal enum class ProbeSetupStatus {
    CANDIDATE,
    INDEXING,
    INDEXING_SCHEDULED,
    INDEXING_UNAVAILABLE,
    VFS_REFRESH_ACTIVE,
    VFS_EVENT_PROCESSING_ACTIVE,
    VFS_REFRESH_UNAVAILABLE,
    EXTERNAL_TASKS_ACTIVE,
    IMPORT_PENDING,
    IMPORT_FAILED,
    GRADLE_MODULE_UNAVAILABLE,
}

@Serializable
internal data class ProbeSetupGeneration(
    val imports: Long,
    val roots: Long,
    val workspace: Long,
    val vfs: Long,
    val psi: Long,
    val dumb: Long,
)

internal data class ProbeSetupSample(
    val status: ProbeSetupStatus,
    val generation: ProbeSetupGeneration,
    val import: ProbeImportState,
    val provenance: ProbeSourceProvenance,
    val indexing: ProbeSetupIndexingState,
    val refresh: ProbeSetupRefreshState,
)

internal class ProbeSetupObservation
private constructor(
    val import: ProbeSetupImportEvidence,
    val before: ProbeSetupSample,
    val after: ProbeSetupSample,
    val drain: ProbeSetupDrainState,
) {
    val provenance: ProbeSourceProvenance
        get() = after.provenance

    companion object {
        fun admit(
            before: ProbeSetupSample,
            after: ProbeSetupSample,
            elapsedNanos: Long,
            drain: ProbeSetupDrainState,
        ): ProbeResult<ProbeSetupObservation> {
            if (after.indexing != ProbeSetupIndexingState.IDLE) return ProbeResult.Rejected(ProbeFailure.SETUP_MOVING)
            if (!after.refresh.idle) return ProbeResult.Rejected(ProbeFailure.SETUP_MOVING)
            if (after.generation.run { listOf(imports, roots, workspace, vfs, psi, dumb).any { it < 0 } })
                return ProbeResult.Rejected(ProbeFailure.SETUP_MOVING)
            if (after.provenance == ProbeSourceProvenance.UNAVAILABLE)
                return ProbeResult.Rejected(ProbeFailure.SETUP_MOVING)
            if (
                before != after || after.status != ProbeSetupStatus.CANDIDATE || elapsedNanos < SETUP_QUIET_WINDOW_NANOS
            ) {
                return ProbeResult.Rejected(ProbeFailure.SETUP_MOVING)
            }
            val evidence =
                when (after.import) {
                    ProbeImportState.FINAL_TASKS_FINISHED -> ProbeSetupImportEvidence.FINAL_TASKS_OBSERVED
                    ProbeImportState.NOT_OBSERVED -> ProbeSetupImportEvidence.NOT_OBSERVED_PERSISTED_MODEL
                    else -> return ProbeResult.Rejected(ProbeFailure.SETUP_IMPORT_PENDING)
                }
            return ProbeResult.Accepted(
                ProbeSetupObservation(import = evidence, before = before, after = after, drain = drain)
            )
        }
    }
}

internal const val SETUP_QUIET_WINDOW_MILLIS = 2000L
internal const val SETUP_QUIET_WINDOW_NANOS = SETUP_QUIET_WINDOW_MILLIS * 1000000L

internal data class ProbeImportProgress(val state: ProbeImportState, val sequence: Long)

internal sealed interface ProbeImportRequirement {
    data object Current : ProbeImportRequirement

    data class After(val sequence: Long) : ProbeImportRequirement

    fun ready(progress: ProbeImportProgress, command: ProbeCommand): Boolean =
        when (this) {
            Current ->
                progress.state == ProbeImportState.FINAL_TASKS_FINISHED ||
                    (command == ProbeCommand.AWAIT_REOPEN_READY && progress.state == ProbeImportState.NOT_OBSERVED)
            is After -> progress.state == ProbeImportState.FINAL_TASKS_FINISHED && progress.sequence > sequence
        }
}

@Serializable
internal enum class ProbeSourceProvenance {
    AUTHORED,
    GENERATED,
    UNAVAILABLE,
}

@Serializable
internal enum class ProbeSetupIndexingState {
    IDLE,
    RUNNING,
    SCHEDULED,
    UNAVAILABLE;

    fun setupStatus(refresh: ProbeSetupRefreshState): ProbeSetupStatus =
        when (this) {
            IDLE -> refresh.status
            RUNNING -> ProbeSetupStatus.INDEXING
            SCHEDULED -> ProbeSetupStatus.INDEXING_SCHEDULED
            UNAVAILABLE -> ProbeSetupStatus.INDEXING_UNAVAILABLE
        }

    companion object {
        fun observe(isDumb: Boolean, hasScheduledTasks: Boolean): ProbeSetupIndexingState =
            when {
                isDumb -> RUNNING
                hasScheduledTasks -> SCHEDULED
                else -> IDLE
            }
    }
}

@Serializable
internal enum class ProbeSetupDrainState {
    COMPLETED
}

@Serializable
internal enum class ProbeSetupQueueState {
    IDLE,
    ACTIVE,
    UNAVAILABLE,
}

internal data class ProbeSetupRefreshState(val scanning: ProbeSetupQueueState, val processing: ProbeSetupQueueState) {
    val idle: Boolean
        get() = scanning == ProbeSetupQueueState.IDLE && processing == ProbeSetupQueueState.IDLE

    val status: ProbeSetupStatus
        get() =
            when {
                scanning == ProbeSetupQueueState.UNAVAILABLE || processing == ProbeSetupQueueState.UNAVAILABLE ->
                    ProbeSetupStatus.VFS_REFRESH_UNAVAILABLE
                scanning == ProbeSetupQueueState.ACTIVE -> ProbeSetupStatus.VFS_REFRESH_ACTIVE
                processing == ProbeSetupQueueState.ACTIVE -> ProbeSetupStatus.VFS_EVENT_PROCESSING_ACTIVE
                else -> ProbeSetupStatus.CANDIDATE
            }
}
