package io.github.amichne.kast.cli

import io.github.amichne.kast.api.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceEnsureResult(
    val workspaceRoot: String,
    val started: Boolean,
    val logFile: String? = null,
    val selected: RuntimeCandidateStatus,
    val schemaVersion: Int = SCHEMA_VERSION,
)
