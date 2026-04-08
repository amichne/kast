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
    val warnings: List<String> = emptyList(),
    val sourceModuleNames: List<String> = emptyList(),
    val dependentModuleNamesBySourceModuleName: Map<String, List<String>> = emptyMap(),
    val schemaVersion: Int = SCHEMA_VERSION,
)
