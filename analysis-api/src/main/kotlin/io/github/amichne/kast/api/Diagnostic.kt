@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class Diagnostic(
    @DocField(description = "Source location where the diagnostic was reported.")
    val location: Location,
    @DocField(description = "Severity level: ERROR, WARNING, or INFO.")
    val severity: DiagnosticSeverity,
    @DocField(description = "Human-readable diagnostic message from the compiler.")
    val message: String,
    @DocField(description = "Optional compiler-specific diagnostic code identifier.")
    val code: String? = null,
)
