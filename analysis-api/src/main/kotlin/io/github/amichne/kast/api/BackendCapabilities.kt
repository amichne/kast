package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class BackendCapabilities(
    val backendName: String,
    val backendVersion: String,
    val workspaceRoot: String,
    val readCapabilities: Set<ReadCapability>,
    val mutationCapabilities: Set<MutationCapability>,
    val limits: ServerLimits,
    val schemaVersion: Int = SCHEMA_VERSION,
)
