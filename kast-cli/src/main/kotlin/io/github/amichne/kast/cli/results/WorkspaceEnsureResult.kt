package io.github.amichne.kast.cli.results

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.cli.RuntimeCandidateStatus
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceEnsureResult(
    val workspaceRoot: String,
    val started: Boolean,
    val logFile: String? = null,
    val selected: RuntimeCandidateStatus,
    val note: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
