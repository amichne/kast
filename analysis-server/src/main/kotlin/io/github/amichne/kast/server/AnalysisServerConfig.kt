package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.continuation.ContinuationCapacity
import io.github.amichne.kast.api.continuation.ContinuationTtl
import java.nio.file.Path
import java.time.Duration
import kotlin.math.ln

data class AnalysisServerConfig(
    val transport: AnalysisTransport = AnalysisTransport.Stdio,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val token: String? = null,
    val requestTimeoutMillis: Long = 30_000,
    val maxResults: Int = 500,
    val maxConcurrentRequests: Int = 4,
    val continuationTtlMillis: Long = 60_000,
    val continuationCapacity: Int = 256,
    val descriptorDirectory: Path? = null,
    val runtimeInstanceId: RuntimeInstanceId? = null,
    val workspaceFileCount: Int = 0,
    val workspaceFileCountProvider: (() -> Int)? = null,
) {
    val typedContinuationTtl: ContinuationTtl
        get() = ContinuationTtl.of(Duration.ofMillis(continuationTtlMillis))

    val typedContinuationCapacity: ContinuationCapacity
        get() = ContinuationCapacity.of(continuationCapacity)

    /**
     * Returns the effective request timeout in milliseconds, scaling up [requestTimeoutMillis]
     * logarithmically for large workspaces (> 1 000 files) to avoid spurious timeouts on slow
     * machines or during first-run indexing. Automatic scaling is capped at 300 seconds
     * (300 000 ms) without reducing the configured base.
     *
     * Formula (for workspaceFileCount > 1 000):
     *   effectiveTimeout = max(
     *       requestTimeoutMillis,
     *       min(requestTimeoutMillis * log2(workspaceFileCount / 1_000), 300_000),
     *   )
     */
    val effectiveRequestTimeoutMillis: Long
        get() {
            val currentWorkspaceFileCount = workspaceFileCountProvider?.invoke() ?: workspaceFileCount
            if (currentWorkspaceFileCount <= 1_000) return requestTimeoutMillis
            val scaleFactor = (ln(currentWorkspaceFileCount.toDouble() / 1_000.0) / ln(2.0)).coerceAtLeast(1.0)
            return (requestTimeoutMillis * scaleFactor).toLong()
                .coerceAtMost(300_000L)
                .coerceAtLeast(requestTimeoutMillis)
        }

    init {
        validate()
    }

    private fun validate() {
        require(continuationTtlMillis > 0) { "Continuation time to live must be positive" }
        require(continuationCapacity > 0) { "Continuation capacity must be positive" }
        val isLoopback = host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)
        require(isLoopback || !token.isNullOrBlank()) {
            "Binding to non-loopback address '$host' requires a non-empty token for security. " +
            "Set the 'token' field or bind to 127.0.0.1 / ::1 / localhost instead."
        }
    }
}
