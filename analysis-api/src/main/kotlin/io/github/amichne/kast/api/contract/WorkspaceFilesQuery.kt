@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesQuery(
    @DocField(description = "Filter to a single module by name. Omit to list all modules.")
    val moduleName: String? = null,
    @DocField(description = "When true, includes individual file paths for each module.", defaultValue = "false")
    val includeFiles: Boolean = false,
)
