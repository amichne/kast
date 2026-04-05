package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class RefreshResult(
    val refreshedFiles: List<String>,
    val removedFiles: List<String> = emptyList(),
    val fullRefresh: Boolean,
    val schemaVersion: Int = SCHEMA_VERSION,
)
