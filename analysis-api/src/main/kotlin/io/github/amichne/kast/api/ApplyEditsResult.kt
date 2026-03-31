package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ApplyEditsResult(
    val applied: List<TextEdit>,
    val affectedFiles: List<String>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
