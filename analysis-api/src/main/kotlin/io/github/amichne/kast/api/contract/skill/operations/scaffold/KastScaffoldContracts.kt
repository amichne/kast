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
enum class WrapperScaffoldMode {
    @SerialName("implement")
    IMPLEMENT,

    @SerialName("replace")
    REPLACE,

    @SerialName("consolidate")
    CONSOLIDATE,

    @SerialName("extract")
    EXTRACT,
}


@Serializable
data class KastScaffoldRequest(
    val workspaceRoot: String? = null,
    val targetFile: String,
    val targetSymbol: String? = null,
    val mode: WrapperScaffoldMode = WrapperScaffoldMode.IMPLEMENT,
    val kind: WrapperNamedSymbolKind? = null,
)


@Serializable
data class KastScaffoldQuery(
    val workspaceRoot: String,
    val targetFile: String,
    val targetSymbol: String? = null,
    val mode: WrapperScaffoldMode = WrapperScaffoldMode.IMPLEMENT,
    val kind: WrapperNamedSymbolKind? = null,
)


@Serializable
data class KastScaffoldTypeHierarchy(
    val root: TypeHierarchyNode,
    val stats: TypeHierarchyStats,
)


@Serializable
sealed interface KastScaffoldResponse

@Serializable
@SerialName("SCAFFOLD_SUCCESS")
data class KastScaffoldSuccessResponse(
    val ok: Boolean = true,
    val query: KastScaffoldQuery,
    val outline: List<OutlineSymbol>,
    val fileContent: String? = null,
    val symbol: Symbol? = null,
    val references: KastScaffoldReferences? = null,
    val typeHierarchy: KastScaffoldTypeHierarchy? = null,
    val insertionPoint: SemanticInsertionResult? = null,
    val logFile: String,
) : KastScaffoldResponse

@Serializable
@SerialName("SCAFFOLD_FAILURE")
data class KastScaffoldFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastScaffoldQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastScaffoldResponse
