package io.github.amichne.kast.cli.results

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.cli.RuntimeCandidateStatus
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceStatusResult(
    val workspaceRoot: String,
    val descriptorDirectory: String,
    val selected: RuntimeCandidateStatus? = null,
    val candidates: List<RuntimeCandidateStatus>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
