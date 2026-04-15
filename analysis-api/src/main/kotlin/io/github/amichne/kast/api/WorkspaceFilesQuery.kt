package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesQuery(
    val moduleName: String? = null,
    val includeFiles: Boolean = false,
)
