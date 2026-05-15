@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class BackendCapabilities(
    @DocField(description = "Identifier of the analysis backend.")
    val backendName: String,
    @DocField(description = "Version string of the analysis backend.")
    val backendVersion: String,
    @DocField(description = "Absolute path of the workspace root directory.")
    val workspaceRoot: String,
    @DocField(description = "Set of read operations this backend supports.")
    val readCapabilities: Set<ReadCapability>,
    @DocField(description = "Set of mutation operations this backend supports.")
    val mutationCapabilities: Set<MutationCapability>,
    @DocField(description = "Server-enforced resource limits.")
    val limits: ServerLimits,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
