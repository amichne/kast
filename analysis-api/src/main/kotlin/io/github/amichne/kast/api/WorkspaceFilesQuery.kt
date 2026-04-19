@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesQuery(
    @DocField(description = "Filter to a single module by name. Omit to list all modules.")
    val moduleName: String? = null,
    @DocField(description = "When true, includes individual file paths for each module.")
    val includeFiles: Boolean = false,
)
