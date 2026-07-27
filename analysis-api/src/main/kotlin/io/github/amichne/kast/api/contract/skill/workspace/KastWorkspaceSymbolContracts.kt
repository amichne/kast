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
data class KastWorkspaceSymbolRequest(
    val workspaceRoot: String? = null,
    val pattern: String,
    val kind: String? = null,
    val maxResults: Int = 100,
    val regex: Boolean = false,
    val includeDeclarationScope: Boolean = false,
)


@Serializable
data class KastWorkspaceSymbolQuery(
    val workspaceRoot: String,
    val pattern: String,
    val kind: String? = null,
    val maxResults: Int = 100,
    val regex: Boolean = false,
    val includeDeclarationScope: Boolean = false,
)


@Serializable
sealed interface KastWorkspaceSymbolResponse

@Serializable
@SerialName("WORKSPACE_SYMBOL_SUCCESS")
data class KastWorkspaceSymbolSuccessResponse(
    val ok: Boolean = true,
    val query: KastWorkspaceSymbolQuery,
    val symbols: List<Symbol>,
    val page: PageInfo? = null,
    val logFile: String,
) : KastWorkspaceSymbolResponse

@Serializable
@SerialName("WORKSPACE_SYMBOL_FAILURE")
data class KastWorkspaceSymbolFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWorkspaceSymbolQuery,
    val logFile: String,
) : KastWorkspaceSymbolResponse
