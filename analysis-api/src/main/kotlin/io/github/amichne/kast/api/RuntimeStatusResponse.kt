package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeStatusResponse(
    val state: RuntimeState,
    val healthy: Boolean,
    val active: Boolean,
    val indexing: Boolean,
    val backendName: String,
    val backendVersion: String,
    val workspaceRoot: String,
    val message: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
