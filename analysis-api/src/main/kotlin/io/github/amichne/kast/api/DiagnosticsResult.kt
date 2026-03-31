package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsResult(
    val diagnostics: List<Diagnostic>,
    val page: PageInfo? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
