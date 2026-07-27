@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeLifecycleAction {
    SHUTDOWN,
    RESTART,
}

@Serializable
data class RuntimeLifecycleResponse(
    @DocField(description = "Lifecycle action accepted by the runtime host.")
    val accepted: Boolean,
    @DocField(description = "Requested lifecycle action.")
    val action: RuntimeLifecycleAction,
    @DocField(description = "Identifier of the analysis backend.")
    val backendName: String,
    @DocField(description = "Version string of the analysis backend.")
    val backendVersion: String,
    @DocField(description = "Absolute path of the workspace root directory.")
    val workspaceRoot: String,
    @DocField(description = "Human-readable lifecycle status message.")
    val message: String? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
