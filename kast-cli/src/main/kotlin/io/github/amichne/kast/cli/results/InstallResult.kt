package io.github.amichne.kast.cli.results

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class InstallResult(
    val instanceName: String,
    val instanceRoot: String,
    val launcherPath: String,
    val schemaVersion: Int = SCHEMA_VERSION,
)
