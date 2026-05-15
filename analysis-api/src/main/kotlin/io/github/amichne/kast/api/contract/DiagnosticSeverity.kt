package io.github.amichne.kast.api.contract

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}
