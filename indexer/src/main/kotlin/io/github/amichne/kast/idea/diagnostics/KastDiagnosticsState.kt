package io.github.amichne.kast.idea.diagnostics

import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.ReferenceCoverageLimitation
import java.nio.file.Path
import java.time.Instant
import kotlin.math.max

internal class KastDiagnosticsState(
    private val maxEvents: Int = 100,
    private val now: () -> Instant = Instant::now,
) {
    private var nextEventId = 1L
    private var current = KastDiagnosticsSnapshot()

    fun snapshot(): KastDiagnosticsSnapshot = current

    fun recordBackendStarting(workspaceRoot: Path): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.BACKEND,
        title = "Kast backend starting",
        detail = workspaceRoot.toString(),
    ) {
        it.copy(
            backendState = KastBackendUiState.STARTING,
            message = "Kast backend is starting",
            workspaceRoot = workspaceRoot.toString(),
        )
    }

    fun recordBackendStarted(transport: AnalysisTransport): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.BACKEND,
        title = "Kast backend started",
        detail = transport.displayName(),
    ) {
        it.copy(
            backendState = KastBackendUiState.READY,
            message = "Kast backend is ready",
            transport = transport.displayName(),
        )
    }

    fun recordBackendStopped(): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.BACKEND,
        title = "Kast backend stopped",
    ) {
        it.copy(
            backendState = KastBackendUiState.STOPPED,
            message = "Kast backend is stopped",
            activeRequests = 0,
        )
    }

    fun recordBackendFailed(error: Throwable): KastActivityEvent = append(
        severity = KastActivitySeverity.ERROR,
        kind = KastActivityKind.BACKEND,
        title = "Kast backend failed",
        detail = error.compactMessage(),
    ) {
        it.copy(
            backendState = KastBackendUiState.DEGRADED,
            message = error.compactMessage(),
            activeRequests = 0,
        )
    }

    fun recordConfigFallback(path: Path, error: Throwable): KastActivityEvent = append(
        severity = KastActivitySeverity.WARNING,
        kind = KastActivityKind.CONFIG,
        title = "Kast config fallback",
        detail = "${path.fileName}: ${error.compactMessage()}",
    ) {
        it.copy(message = "Config load failed; using last valid config")
    }

    fun recordCapabilities(capabilities: BackendCapabilities): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.BACKEND,
        title = "Kast capabilities loaded",
        detail = "${capabilities.readCapabilities.size} read, ${capabilities.mutationCapabilities.size} mutation",
    ) {
        it.copy(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
            capabilities = capabilities,
        )
    }

    fun recordRuntimeStatus(status: RuntimeStatusResponse): KastActivityEvent? {
        current = current.copy(
            backendState = when {
                !status.healthy -> KastBackendUiState.DEGRADED
                status.state == RuntimeState.INDEXING -> KastBackendUiState.INDEXING
                status.state == RuntimeState.READY -> KastBackendUiState.READY
                status.state == RuntimeState.STARTING -> KastBackendUiState.STARTING
                else -> KastBackendUiState.DEGRADED
            },
            message = status.message ?: current.message,
            backendName = status.backendName,
            backendVersion = status.backendVersion,
            workspaceRoot = status.workspaceRoot,
        )
        return null
    }

    fun recordIndexWaitingForIde(): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.INDEX,
        title = "Kast index waiting for IDEA",
        detail = "IDE indexes must settle before the source index runs",
    ) {
        it.copy(
            backendState = it.backendState.indexingIfReady(),
            indexSummary = KastSourceIndexSummary(state = KastIndexState.WAITING_FOR_IDE),
        )
    }

    fun recordIndexHydrating(): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.INDEX,
        title = "Kast source index hydrating",
    ) {
        it.copy(
            backendState = it.backendState.indexingIfReady(),
            indexSummary = KastSourceIndexSummary(state = KastIndexState.HYDRATING),
        )
    }

    fun recordIndexingStarted(): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.INDEX,
        title = "Kast source index started",
    ) {
        it.copy(
            backendState = it.backendState.indexingIfReady(),
            indexSummary = KastSourceIndexSummary(state = KastIndexState.INDEXING),
        )
    }

    fun recordIndexCompleted(summary: KastSourceIndexSummary): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.INDEX,
        title = "Kast source index complete",
        detail = summary.displayText(),
    ) {
        require(
            summary.state == KastIndexState.READY ||
                summary.state == KastIndexState.DEGRADED ||
                summary.state == KastIndexState.FAILED,
        ) {
            "Completed index summary must be ready, degraded, or failed"
        }
        it.copy(
            backendState = if (it.backendState == KastBackendUiState.INDEXING) {
                if (summary.state == KastIndexState.READY) {
                    KastBackendUiState.READY
                } else {
                    KastBackendUiState.DEGRADED
                }
            } else {
                it.backendState
            },
            indexSummary = summary,
        )
    }

    fun recordIndexCancelled(): KastActivityEvent = append(
        severity = KastActivitySeverity.WARNING,
        kind = KastActivityKind.INDEX,
        title = "Kast source index cancelled",
    ) {
        it.copy(indexSummary = it.indexSummary.copy(state = KastIndexState.CANCELLED))
    }

    fun recordIndexFailed(error: Throwable): KastActivityEvent = append(
        severity = KastActivitySeverity.ERROR,
        kind = KastActivityKind.INDEX,
        title = "Kast source index failed",
        detail = error.compactMessage(),
    ) {
        it.copy(
            backendState = KastBackendUiState.DEGRADED,
            message = "Kast reference index failed: ${error.compactMessage()}",
            indexSummary = KastSourceIndexSummary(state = KastIndexState.FAILED, message = error.compactMessage()),
        )
    }

    fun recordOperationStarted(operation: KastBackendOperation): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.OPERATION,
        title = "${operation.displayName} started",
        operation = operation,
    ) {
        it.copy(activeRequests = it.activeRequests + 1)
    }

    fun recordOperationSucceeded(
        operation: KastBackendOperation,
        durationMillis: Long,
    ): KastActivityEvent = append(
        severity = KastActivitySeverity.INFO,
        kind = KastActivityKind.OPERATION,
        title = "${operation.displayName} completed",
        detail = "${durationMillis}ms",
        operation = operation,
        durationMillis = durationMillis,
    ) {
        it.copy(
            activeRequests = max(0, it.activeRequests - 1),
            completedRequests = it.completedRequests + 1,
        )
    }

    fun recordOperationFailed(
        operation: KastBackendOperation,
        durationMillis: Long,
        error: Throwable,
    ): KastActivityEvent = append(
        severity = KastActivitySeverity.ERROR,
        kind = KastActivityKind.OPERATION,
        title = "${operation.displayName} failed",
        detail = "${durationMillis}ms: ${error.compactMessage()}",
        operation = operation,
        durationMillis = durationMillis,
    ) {
        it.copy(
            activeRequests = max(0, it.activeRequests - 1),
            failedRequests = it.failedRequests + 1,
            message = "${operation.displayName} failed",
        )
    }

    private fun append(
        severity: KastActivitySeverity,
        kind: KastActivityKind,
        title: String,
        detail: String? = null,
        operation: KastBackendOperation? = null,
        durationMillis: Long? = null,
        update: (KastDiagnosticsSnapshot) -> KastDiagnosticsSnapshot,
    ): KastActivityEvent {
        val event = KastActivityEvent(
            id = nextEventId++,
            timestamp = now(),
            severity = severity,
            kind = kind,
            title = title,
            detail = detail,
            operation = operation,
            durationMillis = durationMillis,
        )
        val events = (listOf(event) + current.recentEvents).take(maxEvents)
        current = update(current).copy(recentEvents = events)
        return event
    }
}

internal data class KastDiagnosticsSnapshot(
    val backendState: KastBackendUiState = KastBackendUiState.STOPPED,
    val message: String = "Kast backend is stopped",
    val workspaceRoot: String? = null,
    val backendName: String? = null,
    val backendVersion: String? = null,
    val transport: String? = null,
    val capabilities: BackendCapabilities? = null,
    val indexSummary: KastSourceIndexSummary = KastSourceIndexSummary(),
    val activeRequests: Int = 0,
    val completedRequests: Int = 0,
    val failedRequests: Int = 0,
    val recentEvents: List<KastActivityEvent> = emptyList(),
)

internal data class KastActivityEvent(
    val id: Long,
    val timestamp: Instant,
    val severity: KastActivitySeverity,
    val kind: KastActivityKind,
    val title: String,
    val detail: String? = null,
    val operation: KastBackendOperation? = null,
    val durationMillis: Long? = null,
)

internal data class KastOperationToken(
    val operation: KastBackendOperation,
    val startedNanos: Long,
)

internal data class KastSourceIndexSummary(
    val state: KastIndexState = KastIndexState.IDLE,
    val fileCount: Int? = null,
    val identifierCount: Int? = null,
    val moduleCount: Int? = null,
    val importCount: Int? = null,
    val message: String? = null,
    val referenceCoverageLimitations: List<ReferenceCoverageLimitation> = emptyList(),
) {
    fun displayText(): String {
        message?.let { return it }
        return when (state) {
            KastIndexState.IDLE -> "Idle"
            KastIndexState.WAITING_FOR_IDE -> "Waiting for IDEA"
            KastIndexState.HYDRATING -> "Hydrating"
            KastIndexState.INDEXING -> "Indexing"
            KastIndexState.READY -> listOfNotNull(
                fileCount?.let { "$it files" },
                identifierCount?.let { "$it identifiers" },
                moduleCount?.let { "$it modules" },
                importCount?.let { "$it imports" },
            ).ifEmpty { listOf("Ready") }.joinToString(", ")
            KastIndexState.DEGRADED -> "Degraded"
            KastIndexState.FAILED -> "Failed"
            KastIndexState.CANCELLED -> "Cancelled"
        }
    }
}

internal enum class KastBackendUiState(val displayName: String) {
    STOPPED("Stopped"),
    STARTING("Starting"),
    INDEXING("Indexing"),
    READY("Ready"),
    DEGRADED("Degraded"),
}

private fun KastBackendUiState.indexingIfReady(): KastBackendUiState =
    if (this == KastBackendUiState.READY) KastBackendUiState.INDEXING else this
