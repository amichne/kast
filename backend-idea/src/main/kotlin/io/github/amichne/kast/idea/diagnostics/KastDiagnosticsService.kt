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

    override fun dispose() {
        synchronized(lock) {
            listeners.clear()
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
        publish(synchronized(lock) { state.recordBackendStarting(workspaceRoot) })
    }

    fun recordBackendStarted(transport: AnalysisTransport) {
        publish(synchronized(lock) { state.recordBackendStarted(transport) })
    }

    fun recordBackendStopped() {
        publish(synchronized(lock) { state.recordBackendStopped() })
    }

    fun recordBackendFailed(error: Throwable) {
        publish(synchronized(lock) { state.recordBackendFailed(error) })
    }

    fun recordConfigFallback(path: Path, error: Throwable) {
        publish(synchronized(lock) { state.recordConfigFallback(path, error) })
    }

    fun recordCapabilities(capabilities: BackendCapabilities) {
        publish(synchronized(lock) { state.recordCapabilities(capabilities) })
    }

    fun recordRuntimeStatus(status: RuntimeStatusResponse) {
        publish(synchronized(lock) { state.recordRuntimeStatus(status) })
    }

    fun enrichRuntimeStatus(status: RuntimeStatusResponse): RuntimeStatusResponse {
        val indexReady = snapshot().indexSummary.state == KastIndexState.READY
        return if (status.referenceIndexReady == indexReady) status else status.copy(referenceIndexReady = indexReady)
    }

    fun recordIndexWaitingForIde() {
        publish(synchronized(lock) { state.recordIndexWaitingForIde() })
    }

    fun recordIndexHydrating() {
        publish(synchronized(lock) { state.recordIndexHydrating() })
    }

    fun recordIndexingStarted() {
        publish(synchronized(lock) { state.recordIndexingStarted() })
    }

    fun recordIndexCompleted(summary: KastSourceIndexSummary) {
        publish(synchronized(lock) { state.recordIndexCompleted(summary) })
    }

    fun recordIndexCancelled() {
        publish(synchronized(lock) { state.recordIndexCancelled() })
    }

    fun recordIndexFailed(error: Throwable) {
        publish(synchronized(lock) { state.recordIndexFailed(error) })
    }

    fun recordOperationStarted(operation: KastBackendOperation): KastOperationToken {
        val token = KastOperationToken(
            operation = operation,
            startedNanos = System.nanoTime(),
        )
        publish(synchronized(lock) { state.recordOperationStarted(operation) })
        return token
    }

    fun recordOperationSucceeded(token: KastOperationToken) {
        publish(
            synchronized(lock) {
                state.recordOperationSucceeded(
                    operation = token.operation,
                    durationMillis = elapsedMillis(token.startedNanos),
                )
            },
        )
    }

    fun recordOperationFailed(token: KastOperationToken, error: Throwable) {
        publish(
            synchronized(lock) {
                state.recordOperationFailed(
                    operation = token.operation,
                    durationMillis = elapsedMillis(token.startedNanos),
                    error = error,
                )
            },
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private fun publish(event: KastActivityEvent?) {
        val nextSnapshot: KastDiagnosticsSnapshot
        val nextListeners: List<KastDiagnosticsListener>
        synchronized(lock) {
            nextSnapshot = state.snapshot()
            nextListeners = listeners.toList()
        }

        event?.let(::notifyIfNeeded)
        if (nextListeners.isEmpty() || project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                nextListeners.forEach { listener -> listener.snapshotChanged(nextSnapshot) }
            }
        }
    }

    private fun notifyIfNeeded(event: KastActivityEvent) {
        if (event.severity == KastActivitySeverity.INFO) return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(KAST_ACTIVITY_NOTIFICATION_GROUP_ID)
                .createNotification(event.title, event.detail.orEmpty(), event.severity.toNotificationType())
                .notify(project)
        }
    }

    companion object {
        fun getInstance(project: Project): KastDiagnosticsService = project.service()
    }
}
