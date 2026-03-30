package io.github.amichne.kast.server

import java.nio.file.Path
import kotlin.io.path.Path

data class AnalysisServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val token: String? = null,
    val requestTimeoutMillis: Long = 30_000,
    val maxResults: Int = 500,
    val maxConcurrentRequests: Int = 4,
    val descriptorDirectory: Path = defaultDescriptorDirectory(),
) {
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

fun defaultDescriptorDirectory(): Path = System.getenv("KAST_INSTANCE_DIR")
    ?.let(::Path)
    ?: Path(System.getProperty("user.home"), ".kast", "instances")
