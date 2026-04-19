@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    @DocField(description = "Health status string, always \"ok\" when the daemon is responsive.")
    val status: String = "ok",
    @DocField(description = "Identifier of the analysis backend (e.g. \"standalone\" or \"intellij\").")
    val backendName: String,
    @DocField(description = "Version string of the analysis backend.")
    val backendVersion: String,
    @DocField(description = "Absolute path of the workspace root directory.")
    val workspaceRoot: String,
    @DocField(description = "Protocol schema version for forward compatibility.")
    val schemaVersion: Int = SCHEMA_VERSION,
)
