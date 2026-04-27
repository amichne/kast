package io.github.amichne.kast.cli.runtime

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceStatusResult(
    val workspaceRoot: String,
    val descriptorDirectory: String,
    val selected: RuntimeCandidateStatus? = null,
    val candidates: List<RuntimeCandidateStatus>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
