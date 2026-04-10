package io.github.amichne.kast.api

import java.nio.file.Path

sealed interface AnalysisTransport {
    data class UnixDomainSocket(
        val socketPath: Path,
    ) : AnalysisTransport

    data object Stdio : AnalysisTransport

    data class Tcp(
        val host: String,
        val port: Int,
    ) : AnalysisTransport
}
