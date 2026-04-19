@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeStatusResponse(
    @DocField(description = "Current runtime state: STARTING, INDEXING, READY, or DEGRADED.")
    val state: RuntimeState,
    @DocField(description = "True when the daemon is responsive and not in an error state.")
    val healthy: Boolean,
    @DocField(description = "True when the daemon has an active workspace session.")
    val active: Boolean,
    @DocField(description = "True when the daemon is currently indexing the workspace.")
    val indexing: Boolean,
    @DocField(description = "Identifier of the analysis backend.")
    val backendName: String,
    @DocField(description = "Version string of the analysis backend.")
    val backendVersion: String,
    @DocField(description = "Absolute path of the workspace root directory.")
    val workspaceRoot: String,
    @DocField(description = "Human-readable status message with additional context.")
    val message: String? = null,
    @DocField(description = "Active warning messages about the runtime environment.")
    val warnings: List<String> = emptyList(),
    @DocField(description = "Names of source modules discovered in the workspace.")
    val sourceModuleNames: List<String> = emptyList(),
    @DocField(description = "Map from source module name to its dependency module names.")
    val dependentModuleNamesBySourceModuleName: Map<String, List<String>> = emptyMap(),
    @DocField(description = "Protocol schema version for forward compatibility.")
    val schemaVersion: Int = SCHEMA_VERSION,
)
