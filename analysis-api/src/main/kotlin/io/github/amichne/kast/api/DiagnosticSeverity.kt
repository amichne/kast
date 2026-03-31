package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}
