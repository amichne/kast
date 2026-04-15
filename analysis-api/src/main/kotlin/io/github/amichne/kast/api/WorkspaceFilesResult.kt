package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesResult(
    val modules: List<WorkspaceModule>,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class WorkspaceModule(
    val name: String,
    val sourceRoots: List<String>,
    val dependencyModuleNames: List<String>,
    val files: List<String> = emptyList(),
    val fileCount: Int,
)
