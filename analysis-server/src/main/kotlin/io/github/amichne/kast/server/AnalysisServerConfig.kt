package io.github.amichne.kast.server

import java.nio.file.Path
import kotlin.io.path.Path

sealed interface AnalysisTransport {
    data class UnixDomainSocket(
        val socketPath: Path,
    ) : AnalysisTransport

    data object Stdio : AnalysisTransport
}

data class AnalysisServerConfig(
    val transport: AnalysisTransport = AnalysisTransport.Stdio,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val token: String? = null,
    val requestTimeoutMillis: Long = 30_000,
    val maxResults: Int = 500,
    val maxConcurrentRequests: Int = 4,
    val descriptorDirectory: Path? = null,
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

fun defaultDescriptorDirectory(workspaceRoot: Path): Path = System.getenv("KAST_INSTANCE_DIR")
    ?.let(::Path)
    ?.toAbsolutePath()
    ?.normalize()
    ?: workspaceMetadataDirectory(workspaceRoot).resolve("instances")

fun defaultSocketPath(workspaceRoot: Path): Path = workspaceMetadataDirectory(workspaceRoot)
    .resolve("s")

fun workspaceMetadataDirectory(workspaceRoot: Path): Path = workspaceRoot
    .toAbsolutePath()
    .normalize()
    .resolve(".kast")
