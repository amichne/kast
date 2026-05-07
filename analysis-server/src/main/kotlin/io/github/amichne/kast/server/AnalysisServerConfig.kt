package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisTransport
import java.nio.file.Path
import kotlin.math.ln

data class AnalysisServerConfig(
    val transport: AnalysisTransport = AnalysisTransport.Stdio,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val token: String? = null,
    val requestTimeoutMillis: Long = 30_000,
    val maxResults: Int = 500,
    val maxConcurrentRequests: Int = 4,
    val descriptorDirectory: Path? = null,
    val workspaceFileCount: Int = 0,
) {
    /**
     * Returns the effective request timeout in milliseconds, scaling up [requestTimeoutMillis]
     * logarithmically for large workspaces (> 1 000 files) to avoid spurious timeouts on slow
     * machines or during first-run indexing. The result is capped at 300 seconds (300 000 ms).
     *
     * Formula (for workspaceFileCount > 1 000):
     *   effectiveTimeout = requestTimeoutMillis * log2(workspaceFileCount / 1_000)
     *   capped at 300_000 ms
     */
    val effectiveRequestTimeoutMillis: Long
        get() {
            if (workspaceFileCount <= 1_000) return requestTimeoutMillis
            val scaleFactor = (ln(workspaceFileCount.toDouble() / 1_000.0) / ln(2.0)).coerceAtLeast(1.0)
            return (requestTimeoutMillis * scaleFactor).toLong().coerceAtMost(300_000L)
        }

    init {
        validate()
    }

    private fun validate() {
        val isLoopback = host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)
        require(isLoopback || !token.isNullOrBlank()) {
            "Binding to non-loopback address '$host' requires a non-empty token for security. " +
            "Set the 'token' field or bind to 127.0.0.1 / ::1 / localhost instead."
        }
    }
}
