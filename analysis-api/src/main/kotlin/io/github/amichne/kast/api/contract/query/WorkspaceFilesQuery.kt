@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesQuery(
    @DocField(
        description = "Closed file-kind domain fingerprinted by workspace inventory paging.",
        defaultValue = "MIXED",
    )
    val kindDomain: WorkspaceFileKindDomain = WorkspaceFileKindDomain.MIXED,
    @DocField(description = "Filter to a single module by name. Omit to list all modules.")
    val moduleName: String? = null,
    @DocField(description = "When true, includes individual file paths for each module.", defaultValue = "false")
    val includeFiles: Boolean = false,
    @DocField(
        description = "Maximum file paths to return per module when includeFiles is true. Omit to use the server maxResults limit.",
        defaultValue = "null",
    )
    val maxFilesPerModule: Int? = null,
    @DocField(description = "Opaque workspace inventory snapshot handle returned by a metadata request.")
    val snapshotToken: String? = null,
    @DocField(description = "Opaque single-use module page handle returned by the preceding page.")
    val pageToken: String? = null,
)
