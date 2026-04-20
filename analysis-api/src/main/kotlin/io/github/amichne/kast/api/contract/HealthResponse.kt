@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    @DocField(description = "Health status string, always \"ok\" when the daemon is responsive.", defaultValue = "\"ok\"")
    val status: String = "ok",
    @DocField(description = "Identifier of the analysis backend (e.g. \"standalone\" or \"intellij\").")
    val backendName: String,
    @DocField(description = "Version string of the analysis backend.")
    val backendVersion: String,
    @DocField(description = "Absolute path of the workspace root directory.")
    val workspaceRoot: String,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
