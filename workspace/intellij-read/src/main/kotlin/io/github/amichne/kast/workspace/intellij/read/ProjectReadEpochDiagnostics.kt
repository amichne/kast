package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Each label names one equality input; no path, source text or raw platform value is exposed. */
@Serializable
internal enum class ProjectReadEpochSignal {
    WORKSPACE_MODEL,
    PROJECT_ROOT,
    GRADLE_ROOT,
    IMPORT_STATE,
    PSI,
    VFS,
    ROOT_MODEL,
    INDEXING,
}

/** Effect owner for bounded transition evidence, separate from epoch admission and authority. */
internal class ProjectReadEpochDiagnostics(
    private val host: IdeReadHostLifetime,
    private val publish: (String) -> Unit,
) {
    private sealed interface Previous {
        data object Unobserved : Previous

        data class Observed(val state: ProjectReadEpochState) : Previous
    }

    private sealed interface Availability {
        data object Available : Availability

        data class Rejected(val failure: ProjectReadEpochObservationFailure) : Availability
    }

    private var previous: Previous = Previous.Unobserved
    private var availability: Availability = Availability.Available

    /** Called inside the same read/write access as sampling, so competing reads cannot reorder native states. */
    @Synchronized
    fun record(result: Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>) {
        val outcome =
            when (result) {
                is Refinement.Rejected -> {
                    val rejected = Availability.Rejected(result.failure)
                    if (availability == rejected) return
                    availability = rejected
                    ProjectReadEpochDiagnosticOutcome.Rejected(epochDiagnosticFailure(result.failure))
                }
                is Refinement.Refined -> {
                    val event =
                        when (val before = previous) {
                            Previous.Unobserved -> ProjectReadEpochDiagnosticOutcome.Baseline
                            is Previous.Observed -> {
                                val changed = result.value.changedSignalsFrom(before.state)
                                when {
                                    changed.isNotEmpty() -> ProjectReadEpochDiagnosticOutcome.Moved(changed)
                                    availability is Availability.Rejected -> ProjectReadEpochDiagnosticOutcome.Recovered
                                    else -> return
                                }
                            }
                        }
                    previous = Previous.Observed(result.value)
                    availability = Availability.Available
                    event
                }
            }
        publish(Json.encodeToString(ProjectReadEpochDiagnosticDocument(host.value.toString(), outcome)))
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class ProjectReadEpochDiagnosticDocument(
    val host: String,
    val outcome: ProjectReadEpochDiagnosticOutcome,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val event: String = "kast_project_read_epoch",
)

/** MOVED describes observed signal movement, not a count of newly admitted query epochs. */
@Serializable
internal sealed interface ProjectReadEpochDiagnosticOutcome {
    @Serializable @SerialName("BASELINE") data object Baseline : ProjectReadEpochDiagnosticOutcome

    @Serializable
    @SerialName("MOVED")
    data class Moved(val signals: Set<ProjectReadEpochSignal>) : ProjectReadEpochDiagnosticOutcome

    @Serializable @SerialName("RECOVERED") data object Recovered : ProjectReadEpochDiagnosticOutcome

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val failure: ProjectReadEpochDiagnosticFailure) : ProjectReadEpochDiagnosticOutcome
}

@Serializable
internal sealed interface ProjectReadEpochDiagnosticFailure {
    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: ProjectReadEpochUnavailableCause) : ProjectReadEpochDiagnosticFailure

    @Serializable
    @SerialName("OBSERVATION_FAILED")
    data class ObservationFailed(val stage: ProjectReadEpochObservationStage) : ProjectReadEpochDiagnosticFailure
}

@Serializable
internal enum class ProjectReadEpochUnavailableCause {
    WRONG_THREAD,
    PROJECT_DISPOSED,
    PROJECT_NOT_OPEN,
    PROJECT_NOT_INITIALIZED,
    PROJECT_ROOT_UNAVAILABLE,
    PROJECT_ROOT_MALFORMED,
    DUMB_MODE,
    GRADLE_MODEL_UNAVAILABLE,
    GRADLE_MODEL_INCOMPLETE,
    GRADLE_MODEL_AMBIGUOUS,
    GRADLE_ROOT_UNAVAILABLE,
    GRADLE_ROOT_MALFORMED,
    IMPORT_TIMESTAMPS_INCOHERENT,
    VFS_BATCH_LIMIT_EXCEEDED,
    VFS_PATH_MALFORMED,
    SIGNAL_EXHAUSTED,
    READ_PREEMPTED,
}

private fun epochDiagnosticFailure(failure: ProjectReadEpochObservationFailure): ProjectReadEpochDiagnosticFailure {
    val cause =
        when (failure) {
            ProjectReadEpochObservationFailure.WrongThread -> ProjectReadEpochUnavailableCause.WRONG_THREAD
            ProjectReadEpochObservationFailure.ProjectDisposed -> ProjectReadEpochUnavailableCause.PROJECT_DISPOSED
            ProjectReadEpochObservationFailure.ProjectNotOpen -> ProjectReadEpochUnavailableCause.PROJECT_NOT_OPEN
            ProjectReadEpochObservationFailure.ProjectNotInitialized ->
                ProjectReadEpochUnavailableCause.PROJECT_NOT_INITIALIZED
            ProjectReadEpochObservationFailure.ProjectRootUnavailable ->
                ProjectReadEpochUnavailableCause.PROJECT_ROOT_UNAVAILABLE
            ProjectReadEpochObservationFailure.ProjectRootMalformed ->
                ProjectReadEpochUnavailableCause.PROJECT_ROOT_MALFORMED
            ProjectReadEpochObservationFailure.DumbMode -> ProjectReadEpochUnavailableCause.DUMB_MODE
            ProjectReadEpochObservationFailure.GradleModelUnavailable ->
                ProjectReadEpochUnavailableCause.GRADLE_MODEL_UNAVAILABLE
            ProjectReadEpochObservationFailure.GradleModelIncomplete ->
                ProjectReadEpochUnavailableCause.GRADLE_MODEL_INCOMPLETE
            ProjectReadEpochObservationFailure.GradleModelAmbiguous ->
                ProjectReadEpochUnavailableCause.GRADLE_MODEL_AMBIGUOUS
            ProjectReadEpochObservationFailure.GradleRootUnavailable ->
                ProjectReadEpochUnavailableCause.GRADLE_ROOT_UNAVAILABLE
            ProjectReadEpochObservationFailure.GradleRootMalformed ->
                ProjectReadEpochUnavailableCause.GRADLE_ROOT_MALFORMED
            ProjectReadEpochObservationFailure.ImportTimestampsIncoherent ->
                ProjectReadEpochUnavailableCause.IMPORT_TIMESTAMPS_INCOHERENT
            ProjectReadEpochObservationFailure.VfsBatchLimitExceeded ->
                ProjectReadEpochUnavailableCause.VFS_BATCH_LIMIT_EXCEEDED
            ProjectReadEpochObservationFailure.VfsPathMalformed -> ProjectReadEpochUnavailableCause.VFS_PATH_MALFORMED
            ProjectReadEpochObservationFailure.SignalExhausted -> ProjectReadEpochUnavailableCause.SIGNAL_EXHAUSTED
            ProjectReadEpochObservationFailure.ReadPreempted -> ProjectReadEpochUnavailableCause.READ_PREEMPTED
            is ProjectReadEpochObservationFailure.ObservationFailed ->
                return ProjectReadEpochDiagnosticFailure.ObservationFailed(failure.stage)
        }
    return ProjectReadEpochDiagnosticFailure.Unavailable(cause)
}
