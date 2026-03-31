package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class Diagnostic(
    val location: Location,
    val severity: DiagnosticSeverity,
    val message: String,
    val code: String? = null,
)
