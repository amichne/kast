package io.github.amichne.kast.idea.diagnostics

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.ReferenceCoverageLimitation
import io.github.amichne.kast.api.contract.ReferenceCoverage
import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.RuntimeReadinessProgress
import java.nio.file.Path

internal const val KAST_TOOL_WINDOW_ID = "Kast"
internal const val KAST_STATUS_WIDGET_ID = "io.github.amichne.kast.status"
internal const val KAST_ACTIVITY_NOTIFICATION_GROUP_ID = "Kast Activity"

internal fun interface KastDiagnosticsListener {
    fun snapshotChanged(snapshot: KastDiagnosticsSnapshot)
}

@Service(Service.Level.PROJECT)
internal class KastDiagnosticsService(
    private val project: Project,
) : Disposable {
    private val lock = Any()
    private val state = KastDiagnosticsState()
    private val listeners = mutableListOf<KastDiagnosticsListener>()
    private val terminalFailures = KastTerminalFailureDeduplicator()
    private var pendingSnapshot: KastDiagnosticsSnapshot? = null
    private var deliveryScheduled = false
    private var scheduleUiDelivery: ((() -> Unit) -> Unit) = { task ->
        ApplicationManager.getApplication().invokeLater(task)
    }

    internal constructor(
        project: Project,
        scheduleUiDelivery: ((() -> Unit) -> Unit),
    ) : this(project) {
        this.scheduleUiDelivery = scheduleUiDelivery
    }

    override fun dispose() {
        synchronized(lock) {
            listeners.clear()
            pendingSnapshot = null
            deliveryScheduled = false
        }
    }

    fun snapshot(): KastDiagnosticsSnapshot = synchronized(lock) { state.snapshot() }

    fun addListener(parentDisposable: Disposable, listener: KastDiagnosticsListener) {
        synchronized(lock) {
            listeners += listener
        }
        Disposer.register(parentDisposable) {
            synchronized(lock) {
                listeners.remove(listener)
            }
        }
        listener.snapshotChanged(snapshot())
    }

    fun recordBackendStarting(workspaceRoot: Path) {
        publish { state.recordBackendStarting(workspaceRoot) }
    }

    fun recordBackendStarted(transport: AnalysisTransport) {
        publish { state.recordBackendStarted(transport) }
    }

    fun recordBackendStopped() {
        publish(state::recordBackendStopped)
    }

    fun recordBackendFailed(error: Throwable) {
        publish { state.recordBackendFailed(error) }
    }

    fun recordConfigFallback(path: Path, error: Throwable) {
        publish { state.recordConfigFallback(path, error) }
    }

    fun recordCapabilities(capabilities: BackendCapabilities) {
        publish { state.recordCapabilities(capabilities) }
    }

    fun recordRuntimeStatus(status: RuntimeStatusResponse) {
        publish { state.recordRuntimeStatus(status) }
    }

    fun enrichRuntimeStatus(status: RuntimeStatusResponse): RuntimeStatusResponse {
        val index = snapshot().indexSummary
        return status.withReferenceIndex(index)
    }

    fun recordIndexWaitingForIde() {
        publish(state::recordIndexWaitingForIde)
    }

    fun recordIndexHydrating() {
        publish(state::recordIndexHydrating)
    }

    fun recordIndexingStarted() {
        publish(state::recordIndexingStarted)
    }

    fun recordIndexCompleted(summary: KastSourceIndexSummary) {
        publish { state.recordIndexCompleted(summary) }
    }

    fun recordIndexCancelled() {
        publish(state::recordIndexCancelled)
    }

    fun recordIndexFailed(error: Throwable) {
        publish { state.recordIndexFailed(error) }
    }

    fun recordOperationStarted(operation: KastBackendOperation): KastOperationToken {
        val token = KastOperationToken(
            operation = operation,
            startedNanos = System.nanoTime(),
        )
        publish { state.recordOperationStarted(operation) }
        return token
    }

    fun recordOperationSucceeded(token: KastOperationToken) {
        publish(
            {
                state.recordOperationSucceeded(
                    operation = token.operation,
                    durationMillis = elapsedMillis(token.startedNanos),
                )
            },
        )
    }

    fun recordOperationFailed(token: KastOperationToken, error: Throwable) {
        publish(
            {
                state.recordOperationFailed(
                    operation = token.operation,
                    durationMillis = elapsedMillis(token.startedNanos),
                    error = error,
                )
            },
        )
    }

    fun notifyTerminalFailure(title: String, detail: String) {
        if (!terminalFailures.first(title, detail)) return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Kast")
                .createNotification(title, detail, com.intellij.notification.NotificationType.ERROR)
                .notify(project)
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private fun publish(update: () -> KastActivityEvent?) {
        val (event, shouldSchedule) = synchronized(lock) {
            val event = update()
            val shouldSchedule = listeners.isNotEmpty() && !project.isDisposed && !deliveryScheduled
            if (listeners.isNotEmpty() && !project.isDisposed) {
                pendingSnapshot = state.snapshot()
                deliveryScheduled = true
            }
            event to shouldSchedule
        }
        event?.let(::notifyIfNeeded)
        if (shouldSchedule) {
            runCatching {
                scheduleUiDelivery(::deliverPendingSnapshot)
            }.onFailure {
                synchronized(lock) {
                    deliveryScheduled = false
                }
            }
        }
    }

    private fun deliverPendingSnapshot() {
        val delivery = synchronized(lock) {
            deliveryScheduled = false
            pendingSnapshot?.let { snapshot -> snapshot to listeners.toList() }
                .also { pendingSnapshot = null }
        } ?: return
        if (!project.isDisposed) {
            delivery.second.forEach { listener -> listener.snapshotChanged(delivery.first) }
        }
    }

    private fun notifyIfNeeded(event: KastActivityEvent) {
        if (!event.isActionableTerminalFailure()) return
        notifyTerminalFailure(event.title, event.detail.orEmpty())
    }

    companion object {
        fun getInstance(project: Project): KastDiagnosticsService = project.service()
    }
}

internal class KastTerminalFailureDeduplicator {
    private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun first(title: String, detail: String): Boolean =
        keys.add("$title\u0000$detail")
}

internal fun KastActivityEvent.isActionableTerminalFailure(): Boolean =
    severity == KastActivitySeverity.ERROR &&
        (kind == KastActivityKind.BACKEND || kind == KastActivityKind.INDEX)

internal fun RuntimeStatusResponse.withReferenceIndex(
    index: KastSourceIndexSummary,
): RuntimeStatusResponse {
    val limitations = index.referenceCoverageLimitations.ifEmpty {
        when (index.state) {
            KastIndexState.READY -> emptyList()
            KastIndexState.INDEXING -> listOf(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS)
            KastIndexState.DEGRADED -> listOf(ReferenceCoverageLimitation.NONCRITICAL_STAGE_GAP)
            KastIndexState.FAILED -> listOf(ReferenceCoverageLimitation.CRITICAL_STAGE_GAP)
            KastIndexState.WAITING_FOR_IDE, KastIndexState.HYDRATING ->
                listOf(ReferenceCoverageLimitation.PROJECT_MODEL_UNAVAILABLE)
            KastIndexState.CANCELLED -> listOf(ReferenceCoverageLimitation.CANCELLED)
            KastIndexState.IDLE -> listOf(ReferenceCoverageLimitation.INDEX_NOT_COMMITTED)
        }
    }
    val coverage = when (index.state) {
        KastIndexState.READY -> ReferenceCoverage.complete(limitations)
        KastIndexState.INDEXING -> ReferenceCoverage.qualified(
            limitations = limitations,
            indexReady = false,
        )
        KastIndexState.DEGRADED -> ReferenceCoverage.qualified(
            limitations = limitations,
            indexReady = true,
        )
        KastIndexState.FAILED -> ReferenceCoverage.incomplete(limitations)
        KastIndexState.IDLE,
        KastIndexState.WAITING_FOR_IDE,
        KastIndexState.HYDRATING,
        KastIndexState.CANCELLED,
        -> ReferenceCoverage.unavailable(limitations)
    }
    val covered = withReferenceCoverage(coverage)
    if (covered.readiness.references !is RuntimeReadinessLane.InProgress) return covered
    val now = System.currentTimeMillis()
    val started = index.stageStartedAtEpochMillis ?: now
    val lastProgress = index.lastProgressAtEpochMillis ?: started
    val elapsed = (now - started).coerceAtLeast(0)
    val noProgress = (now - lastProgress).coerceIn(0, elapsed)
    val total = index.fileCount?.toLong() ?: 0L
    val completed = if (index.state == KastIndexState.READY) total else 0L
    val referenceLane = RuntimeReadinessLane.InProgress(
        RuntimeReadinessProgress(
            stage = when (index.state) {
                KastIndexState.WAITING_FOR_IDE -> RuntimeProgressStage.IDE_INDEXING
                KastIndexState.HYDRATING -> RuntimeProgressStage.MODEL_SETTLEMENT
                else -> RuntimeProgressStage.REFERENCE_INDEX
            },
            completedUnits = completed,
            totalUnits = total,
            elapsedMillis = elapsed,
            noProgressMillis = noProgress,
        ),
    )
    val readiness = covered.readiness.copy(references = referenceLane)
    return covered.copy(readiness = readiness, ready = readiness.readySummary)
}
