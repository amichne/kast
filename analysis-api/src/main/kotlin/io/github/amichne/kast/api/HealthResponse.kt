package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val backendName: String,
    val backendVersion: String,
    val workspaceRoot: String,
    val schemaVersion: Int = SCHEMA_VERSION,
)
