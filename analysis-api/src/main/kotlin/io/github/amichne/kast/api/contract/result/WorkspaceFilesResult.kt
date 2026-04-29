@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesResult(
    @DocField(description = "List of workspace modules visible to the daemon.")
    val modules: List<WorkspaceModule>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class WorkspaceModule(
    @DocField(description = "Module name as identified by the build system.")
    val name: String,
    @DocField(description = "Absolute paths of the module's source root directories.")
    val sourceRoots: List<String>,
    @DocField(description = "Names of other modules this module depends on.")
    val dependencyModuleNames: List<String>,
    @DocField(description = "Individual source file paths, populated when includeFiles is true.", defaultValue = "emptyList()")
    val files: List<String> = emptyList(),
    @DocField(description = "True when the files list was capped before every source file path could be returned.", defaultValue = "false")
    val filesTruncated: Boolean = false,
    @DocField(description = "Total number of source files in this module.")
    val fileCount: Int,
)
