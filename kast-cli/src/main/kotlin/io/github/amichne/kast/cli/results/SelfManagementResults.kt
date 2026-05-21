package io.github.amichne.kast.cli.results

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.cli.InstallManifest
import kotlinx.serialization.Serializable

@Serializable
internal data class SelfStatusResult(
    val installed: Boolean,
    val manifestPath: String,
    val manifest: InstallManifest? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
internal data class SelfDoctorResult(
    val installed: Boolean,
    val manifestPath: String,
    val ok: Boolean,
    val issues: List<String>,
    val warnings: List<String>,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
internal data class SelfUninstallResult(
    val skipped: Boolean,
    val removedManagedPaths: List<String>,
    val cleanedShellRcFiles: List<String>,
    val removedManifest: Boolean,
    val removedInstallRoot: Boolean,
    val schemaVersion: Int = SCHEMA_VERSION,
)
