package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the latest workspace preparation in one Host did. */
@Serializable
sealed interface HostPreparationProgress {
    /** No preparation was requested in this Host lifetime. */
    @Serializable @SerialName("none") data object None : HostPreparationProgress

    @Serializable
    @SerialName("running")
    data class Running(val stage: IdeLifecycleStage, val startedAtEpochMillis: Long) : HostPreparationProgress

    @Serializable @SerialName("ready") data object Ready : HostPreparationProgress

    @Serializable
    @SerialName("blocked")
    data class Blocked(val stage: IdeLifecycleStage, val failure: IdeLifecycleFailure) : HostPreparationProgress
}

/** Whether a Gradle model resolution runs in an open project. */
@Serializable
enum class HostModelActivity {
    IDLE,
    RESOLVING,
}

/** The index work of the open projects. File counts are the platform's own estimates. */
@Serializable
sealed interface HostIndexProgress {
    @Serializable @SerialName("no_project") data object NoProject : HostIndexProgress

    @Serializable @SerialName("smart") data object Smart : HostIndexProgress

    @Serializable @SerialName("scanning") data class Scanning(val queuedFiles: Int) : HostIndexProgress

    /** [queuedFiles] is the largest queue seen since the preceding scan, not the files that remain. */
    @Serializable @SerialName("indexing") data class Indexing(val queuedFiles: Int) : HostIndexProgress
}

/**
 * The latest progress facts that one managed Host observed about itself. The record grants no readiness. A reader must
 * match [hostLifetime] with the live Host before it presents these facts, because the file survives its writer.
 */
@Serializable
data class HostProgressRecord(
    val hostLifetime: String,
    val preparation: HostPreparationProgress,
    val model: HostModelActivity,
    val index: HostIndexProgress,
    /** When these facts last changed. */
    val changedAtEpochMillis: Long,
    @Required @EncodeDefault val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** An approximate, presentation-only phase. Platform work overlaps, so the earliest unfinished phase wins. */
    val phase: HostPhase
        get() =
            when (val current = preparation) {
                is HostPreparationProgress.Blocked -> HostPhase.BLOCKED
                is HostPreparationProgress.Running ->
                    if (current.stage == IdeLifecycleStage.OPENING) HostPhase.OPENING
                    else activePhase() ?: stagePhase(current.stage)
                HostPreparationProgress.Ready -> activePhase() ?: HostPhase.READY
                HostPreparationProgress.None -> activePhase() ?: HostPhase.STARTING
            }

    private fun activePhase(): HostPhase? =
        if (model == HostModelActivity.RESOLVING) HostPhase.IMPORTING
        else
            when (index) {
                is HostIndexProgress.Scanning -> HostPhase.SCANNING
                is HostIndexProgress.Indexing -> HostPhase.INDEXING
                HostIndexProgress.NoProject,
                HostIndexProgress.Smart -> null
            }

    private fun stagePhase(stage: IdeLifecycleStage): HostPhase =
        when (stage) {
            IdeLifecycleStage.OPENING -> HostPhase.OPENING
            IdeLifecycleStage.IMPORTING -> HostPhase.IMPORTING
            IdeLifecycleStage.ADMISSION,
            IdeLifecycleStage.PRESENTING,
            IdeLifecycleStage.CLOSING -> HostPhase.ADMITTING
        }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val FILE_NAME: String = "host-progress.json"
    }
}

enum class HostPhase {
    STARTING,
    OPENING,
    IMPORTING,
    SCANNING,
    INDEXING,
    ADMITTING,
    READY,
    BLOCKED,
}
