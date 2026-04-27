package io.github.amichne.kast.cli.runtime

import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import kotlinx.serialization.Serializable

@Serializable
internal data class RuntimeCandidateStatus(
    val descriptorPath: String,
    val descriptor: ServerInstanceDescriptor,
    val pidAlive: Boolean,
    val reachable: Boolean,
    val ready: Boolean,
    val runtimeStatus: RuntimeStatusResponse? = null,
    val capabilities: BackendCapabilities? = null,
    val errorMessage: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
