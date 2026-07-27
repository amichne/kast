package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.protocol.ApiErrorResponse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class KastWorkspaceSearchRequest(
    val workspaceRoot: String? = null,
    val pattern: String,
    val regex: Boolean = false,
    val maxResults: Int = 100,
    val fileGlob: String? = null,
    val caseSensitive: Boolean = false,
)


@Serializable
data class KastWorkspaceSearchQuery(
    val workspaceRoot: String,
    val pattern: String,
    val regex: Boolean = false,
    val maxResults: Int = 100,
    val fileGlob: String? = null,
    val caseSensitive: Boolean = false,
)


@Serializable
sealed interface KastWorkspaceSearchResponse

@Serializable
@SerialName("WORKSPACE_SEARCH_SUCCESS")
data class KastWorkspaceSearchSuccessResponse(
    val ok: Boolean = true,
    val query: KastWorkspaceSearchQuery,
    val matches: List<SearchMatch>,
    val truncated: Boolean,
    val schemaVersion: Int,
    val logFile: String,
) : KastWorkspaceSearchResponse

@Serializable
@SerialName("WORKSPACE_SEARCH_FAILURE")
data class KastWorkspaceSearchFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWorkspaceSearchQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastWorkspaceSearchResponse
