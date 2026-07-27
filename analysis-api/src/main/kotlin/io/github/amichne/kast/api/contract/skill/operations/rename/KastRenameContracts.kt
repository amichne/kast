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
sealed interface KastRenameRequest

@Serializable
@SerialName("RENAME_BY_SYMBOL_REQUEST")
data class KastRenameBySymbolRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val newName: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
) : KastRenameRequest

@Serializable
@SerialName("RENAME_BY_OFFSET_REQUEST")
data class KastRenameByOffsetRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val offset: Int,
    val newName: String,
) : KastRenameRequest

@Serializable
@SerialName("RENAME_BY_SELECTOR_HANDLE_REQUEST")
data class KastRenameBySelectorHandleRequest(
    val workspaceRoot: String? = null,
    val selectorHandle: String,
    val newName: String,
) : KastRenameRequest


@Serializable
data class KastRenameFailureQuery(
    val type: String? = null,
    val workspaceRoot: String,
    val symbol: String? = null,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val filePath: String? = null,
    val offset: Int? = null,
    val newName: String,
)

@Serializable
sealed interface KastRenameQuery

@Serializable
@SerialName("RENAME_BY_SYMBOL_REQUEST")
data class KastRenameBySymbolQuery(
    val workspaceRoot: String,
    val symbol: String,
    val newName: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val filePath: String,
    val offset: Int,
) : KastRenameQuery

@Serializable
@SerialName("RENAME_BY_OFFSET_REQUEST")
data class KastRenameByOffsetQuery(
    val workspaceRoot: String,
    val filePath: String,
    val offset: Int,
    val newName: String,
) : KastRenameQuery

@Serializable
@SerialName("RENAME_BY_SELECTOR_HANDLE_REQUEST")
data class KastRenameBySelectorHandleQuery(
    val workspaceRoot: String,
    val selectorHandle: String,
    val newName: String,
    val filePath: String,
    val offset: Int,
) : KastRenameQuery


@Serializable
sealed interface KastRenameResponse

@Serializable
@SerialName("RENAME_SUCCESS")
data class KastRenameSuccessResponse(
    val ok: Boolean,
    val query: KastRenameQuery,
    val editCount: Int,
    val affectedFiles: List<String>,
    val applyResult: ApplyEditsResult,
    val diagnostics: KastDiagnosticsSummary,
    val logFile: String,
) : KastRenameResponse

@Serializable
@SerialName("RENAME_FAILURE")
data class KastRenameFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastRenameFailureQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastRenameResponse
