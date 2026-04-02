package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyResult(
    val root: CallNode,
    val totalNodes: Int,
    val totalEdges: Int,
    val persistedSnapshot: PersistedCallHierarchySnapshot? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class PersistedCallHierarchySnapshot(
    val gitSha: String?,
    val relativePath: String,
)
