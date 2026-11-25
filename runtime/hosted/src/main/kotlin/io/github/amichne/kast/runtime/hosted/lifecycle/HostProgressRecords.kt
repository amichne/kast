package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.UnindexedFilesScannerExecutor
import com.intellij.util.indexing.PerProjectIndexingQueue
import io.github.amichne.kast.protocol.contract.HostIndexProgress
import io.github.amichne.kast.protocol.contract.HostModelActivity
import io.github.amichne.kast.protocol.contract.HostPreparationProgress
import io.github.amichne.kast.protocol.contract.HostProgressRecord
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Native facts of one observation. [queuedFiles] is the platform's estimate for work that no indexer has taken. */
internal data class HostNativeActivity(
    val open: Boolean,
    val resolving: Boolean,
    val scanning: Boolean,
    val dumb: Boolean,
    val queuedFiles: Int,
)

@Serializable
internal enum class HostProgressRecordOutcome {
    WRITTEN,
    FAILED,
}

/**
 * The only Host writer of `host-progress.json`. It writes when a fact changes. A failed write keeps the earlier record
 * and is tried again on the next observation; progress never delays or fails the work it describes.
 */
internal class HostProgressRecords(
    state: Path,
    private val hostLifetime: String,
    private val hostPid: Long,
    private val now: () -> Long,
    private val observe: (HostProgressRecordOutcome) -> Unit,
) {
    private val file = HostStateFile(state, HostProgressRecord.FILE_NAME)
    private var preparation: HostPreparationProgress = HostPreparationProgress.None
    private var queuedFiles = 0
    private var written: HostProgressRecord? = null
    private var reported: HostProgressRecordOutcome? = null

    @Synchronized
    fun prepared(observation: NativePreparationObservation) {
        val stage = observation.preparation.stage
        preparation =
            when (observation.outcome) {
                NativePreparationOutcome.STARTED -> HostPreparationProgress.Running(stage, now())
                NativePreparationOutcome.STAGE_CHANGED ->
                    HostPreparationProgress.Running(
                        stage,
                        (preparation as? HostPreparationProgress.Running)?.startedAtEpochMillis ?: now(),
                    )
                NativePreparationOutcome.COMPLETE -> HostPreparationProgress.Ready
                NativePreparationOutcome.BLOCKED ->
                    observation.failure?.let { HostPreparationProgress.Blocked(stage, it) } ?: preparation
            }
    }

    @Synchronized
    fun record(activity: HostNativeActivity) {
        val facts =
            HostProgressRecord(
                hostLifetime,
                hostPid,
                preparation,
                if (activity.resolving) HostModelActivity.RESOLVING else HostModelActivity.IDLE,
                index(activity),
                now(),
            )
        if (written?.copy(changedAtEpochMillis = facts.changedAtEpochMillis) == facts) return
        report(write(facts))
    }

    private fun index(activity: HostNativeActivity): HostIndexProgress =
        when {
            !activity.open -> HostIndexProgress.NoProject
            activity.dumb -> {
                queuedFiles = maxOf(queuedFiles, activity.queuedFiles)
                HostIndexProgress.Indexing(queuedFiles)
            }
            activity.scanning -> {
                queuedFiles = activity.queuedFiles
                HostIndexProgress.Scanning(queuedFiles)
            }
            else -> {
                queuedFiles = 0
                HostIndexProgress.Smart
            }
        }

    private fun write(facts: HostProgressRecord): HostProgressRecordOutcome =
        when (file.replace(JSON.encodeToString(HostProgressRecord.serializer(), facts))) {
            HostStateReplacement.REPLACED -> {
                written = facts
                HostProgressRecordOutcome.WRITTEN
            }
            HostStateReplacement.FAILED -> HostProgressRecordOutcome.FAILED
        }

    /** Each change of outcome is reported once, so a full disk cannot flood the log. */
    private fun report(outcome: HostProgressRecordOutcome) {
        if (outcome == reported) return
        reported = outcome
        observe(outcome)
    }

    companion object {
        private val JSON = Json { encodeDefaults = true }

        /** Desktop Hosts have no private state, so nothing records their progress. */
        fun forManagedHost(host: ManagedHeadlessHost?, hostLifetime: String): HostProgressRecords? =
            host?.let {
                val logger = Logger.getInstance(HostProgressRecords::class.java)
                HostProgressRecords(
                    it.environment.state,
                    hostLifetime,
                    ProcessHandle.current().pid(),
                    System::currentTimeMillis,
                ) { outcome ->
                    logger.info("kast_host_progress_record outcome=$outcome")
                }
            }
    }
}

/** Each observation reads flags only. It takes no read lock, so it never waits for the work it describes. */
internal fun launchHostProgress(scope: CoroutineScope, records: HostProgressRecords?): Job? =
    records?.let {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    it.record(observeHostNativeActivity())
                } catch (_: ProcessCanceledException) {
                    // A project closed during this observation; the next one sees the projects that remain.
                    ensureActive()
                }
                delay(PROGRESS_POLL_MILLIS)
            }
        }
    }

private suspend fun observeHostNativeActivity(): HostNativeActivity {
    val projects = observeOpenHostProjects()
    val processing = ExternalSystemProcessingManager.getInstance()
    return HostNativeActivity(
        open = projects.isNotEmpty(),
        resolving = projects.any { processing.hasTaskOfTypeInProgress(ExternalSystemTaskType.RESOLVE_PROJECT, it) },
        scanning =
            projects.any {
                val scanner = UnindexedFilesScannerExecutor.getInstance(it)
                scanner.isRunning.value || scanner.hasQueuedTasks
            },
        dumb = projects.any(DumbService::isDumb),
        queuedFiles = projects.sumOf { queuedFiles(it) },
    )
}

private suspend fun queuedFiles(project: Project): Int =
    withTimeoutOrNull(QUEUE_READ_MILLIS) {
        project.getService(PerProjectIndexingQueue::class.java).estimatedFilesCount().first()
    } ?: 0

private const val PROGRESS_POLL_MILLIS = 500L
private const val QUEUE_READ_MILLIS = 100L
