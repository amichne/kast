package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostIndexProgress
import io.github.amichne.kast.protocol.contract.HostModelActivity
import io.github.amichne.kast.protocol.contract.HostPhase
import io.github.amichne.kast.protocol.contract.HostPreparationProgress
import io.github.amichne.kast.protocol.contract.HostProgressRecord
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale

/** Why no progress facts belong to the live Host. */
internal enum class WorkspaceProgressFailure {
    HOST_NOT_OBSERVED,
    NOT_RECORDED,
    EARLIER_HOST,
    INVALID_RECORD,
}

/** Reads the Host's own progress facts. Only the live Host's record counts; the file survives its writer. */
internal fun workspaceProgress(
    state: Path,
    host: Observation<LoadedApplicationHost>,
): Observation<HostProgressRecord> {
    val lifetime = host.value?.runtimeIdentity?.hostLifetime
    val path = state.resolve(HostProgressRecord.FILE_NAME)
    return when {
        lifetime == null -> unobserved(WorkspaceProgressFailure.HOST_NOT_OBSERVED)
        Files.notExists(path, NOFOLLOW_LINKS) -> unobserved(WorkspaceProgressFailure.NOT_RECORDED)
        else ->
            when (val read = containerFiles { readContainerRecord<HostProgressRecord>(path) }) {
                is Refinement.Rejected -> unobserved(WorkspaceProgressFailure.INVALID_RECORD)
                is Refinement.Refined ->
                    when {
                        read.value.schemaVersion != HostProgressRecord.SCHEMA_VERSION ->
                            unobserved(WorkspaceProgressFailure.INVALID_RECORD)
                        read.value.hostLifetime != lifetime -> unobserved(WorkspaceProgressFailure.EARLIER_HOST)
                        else -> Observation.verified(read.value)
                    }
            }
    }
}

private fun unobserved(failure: WorkspaceProgressFailure): Observation<HostProgressRecord> =
    Observation.unavailable(failure.name)

/** Nothing when no Host runs, because the Host line already says so. */
internal fun workspaceProgressLine(progress: Observation<HostProgressRecord>): String? =
    progress.value?.let { "Workspace progress: ${progressText(it)}" }
        ?: when (progress.reason) {
            WorkspaceProgressFailure.HOST_NOT_OBSERVED.name -> null
            WorkspaceProgressFailure.NOT_RECORDED.name -> "Workspace progress: not recorded by this Host"
            WorkspaceProgressFailure.EARLIER_HOST.name -> "Workspace progress: none recorded since this Host started"
            else -> "Workspace progress: could not tell (${progress.reason.orEmpty().lowercase().replace('_', ' ')})"
        }

private fun progressText(record: HostProgressRecord): String =
    when (val phase = record.phase) {
        HostPhase.STARTING -> "Host started; no preparation requested yet"
        HostPhase.READY -> "prepared; no import or index work"
        HostPhase.BLOCKED -> "failed while ${blockedText(record)}"
        else -> {
            val step = STEPS.indexOf(phase) + 1
            val also = overlappingIndexText(record, phase)?.let { "; also $it" }.orEmpty()
            "step $step of ${STEPS.size}, ${phaseText(phase, record.index)}$also"
        }
    }

private val STEPS =
    listOf(
        HostPhase.OPENING,
        HostPhase.IMPORTING,
        HostPhase.SCANNING,
        HostPhase.INDEXING,
        HostPhase.ADMITTING,
    )

private fun phaseText(phase: HostPhase, index: HostIndexProgress): String =
    when (phase) {
        HostPhase.OPENING -> "opening the project"
        HostPhase.IMPORTING -> "importing the Gradle model"
        HostPhase.SCANNING,
        HostPhase.INDEXING -> indexText(index).orEmpty()
        HostPhase.ADMITTING -> "checking readiness"
        HostPhase.STARTING,
        HostPhase.READY,
        HostPhase.BLOCKED -> phase.name.lowercase()
    }

/** Indexing overlaps the project open and the Gradle import. Presenters name both. */
private fun overlappingIndexText(record: HostProgressRecord, phase: HostPhase): String? =
    indexText(record.index).takeIf { phase == HostPhase.OPENING || phase == HostPhase.IMPORTING }

private fun indexText(index: HostIndexProgress): String? =
    when (index) {
        is HostIndexProgress.Scanning -> "scanning files" + queuedText(index.queuedFiles)
        is HostIndexProgress.Indexing -> "indexing" + queuedText(index.queuedFiles)
        HostIndexProgress.NoProject,
        HostIndexProgress.Smart -> null
    }

private fun queuedText(files: Int): String =
    if (files > 0) " (about ${String.format(Locale.ROOT, "%,d", files)} files)" else ""

private fun blockedText(record: HostProgressRecord): String {
    val blocked = record.preparation as? HostPreparationProgress.Blocked ?: return "preparing"
    val doing =
        when (blocked.stage) {
            IdeLifecycleStage.OPENING -> "opening the project"
            IdeLifecycleStage.IMPORTING -> "importing the Gradle model"
            IdeLifecycleStage.ADMISSION -> "checking readiness"
            IdeLifecycleStage.PRESENTING,
            IdeLifecycleStage.CLOSING -> "preparing"
        }
    val still = if (record.model == HostModelActivity.RESOLVING) "; Gradle still runs" else ""
    return "$doing (${blocked.failure.name.lowercase().replace('_', ' ')})$still"
}
