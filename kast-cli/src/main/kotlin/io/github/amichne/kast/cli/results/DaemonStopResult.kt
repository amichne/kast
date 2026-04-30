package io.github.amichne.kast.cli.results

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class DaemonStopResult(
    val workspaceRoot: String,
    val stopped: Boolean,
    val descriptorPath: String? = null,
    val pid: Long? = null,
    val forced: Boolean = false,
    val schemaVersion: Int = SCHEMA_VERSION,
)
