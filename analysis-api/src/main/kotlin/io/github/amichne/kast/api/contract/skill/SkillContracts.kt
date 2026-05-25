package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.protocol.ApiErrorResponse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WrapperNamedSymbolKind {
    @SerialName("class")
    CLASS,

    @SerialName("interface")
    INTERFACE,

    @SerialName("object")
    OBJECT,

    @SerialName("function")
    FUNCTION,

    @SerialName("property")
    PROPERTY,
}

@Serializable
enum class WrapperCallDirection {
    @SerialName("incoming")
    INCOMING,

    @SerialName("outgoing")
    OUTGOING,
}

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
data class KastResolveRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclarationScope: Boolean = false,
    val includeDocumentation: Boolean = false,
    val surroundingLines: Int? = null,
    val includeSurroundingMembers: Boolean = false,
)

@Serializable
data class KastDiscoverRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val line: Int? = null,
    val codeSnippet: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val maxResults: Int = 10,
    val includeDeclarationScope: Boolean = false,
)

@Serializable
data class KastReferencesRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclaration: Boolean = true,
)

@Serializable
data class KastCallersRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val direction: WrapperCallDirection = WrapperCallDirection.INCOMING,
    val depth: Int = 2,
    val maxTotalCalls: Int? = null,
    val maxChildrenPerNode: Int? = null,
    val timeoutMillis: Int? = null,
)

@Serializable
data class KastDiagnosticsRequest(
    val workspaceRoot: String? = null,
    val filePaths: List<String>,
)

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
data class KastScaffoldRequest(
    val workspaceRoot: String? = null,
    val targetFile: String,
    val targetSymbol: String? = null,
    val mode: WrapperScaffoldMode = WrapperScaffoldMode.IMPLEMENT,
    val kind: WrapperNamedSymbolKind? = null,
)

@Serializable
data class KastWorkspaceFilesRequest(
    val workspaceRoot: String? = null,
    val moduleName: String? = null,
    val includeFiles: Boolean = false,
    val maxFilesPerModule: Int? = null,
)

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
data class KastFileOutlineRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
)

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
sealed interface KastWriteAndValidateRequest

@Serializable
@SerialName("CREATE_FILE_REQUEST")
data class KastWriteAndValidateCreateFileRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val content: String? = null,
    val contentFile: String? = null,
) : KastWriteAndValidateRequest

@Serializable
@SerialName("INSERT_AT_OFFSET_REQUEST")
data class KastWriteAndValidateInsertAtOffsetRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val offset: Int,
    val content: String? = null,
    val contentFile: String? = null,
) : KastWriteAndValidateRequest

@Serializable
@SerialName("REPLACE_RANGE_REQUEST")
data class KastWriteAndValidateReplaceRangeRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val content: String? = null,
    val contentFile: String? = null,
) : KastWriteAndValidateRequest

@Serializable
data class KastResolveQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclarationScope: Boolean = false,
    val includeDocumentation: Boolean = false,
    val surroundingLines: Int? = null,
    val includeSurroundingMembers: Boolean = false,
)

@Serializable
data class KastDiscoverQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val line: Int? = null,
    val codeSnippet: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val maxResults: Int = 10,
    val includeDeclarationScope: Boolean = false,
)

@Serializable
data class KastResolveParams(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
)

@Serializable
data class KastNextRequest(
    val method: String,
    val params: KastResolveParams,
)

@Serializable
data class KastDiscoveryCandidate(
    val rank: Int,
    val confidence: Double,
    val symbol: Symbol,
    val reasons: List<String>,
    val resolveParams: KastResolveParams,
    val nextRequest: KastNextRequest,
)

@Serializable
data class KastSourceTextWindow(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

@Serializable
data class KastResolveContext(
    val surroundingText: KastSourceTextWindow? = null,
    val surroundingMembers: List<Symbol>? = null,
)

@Serializable
data class KastReferencesQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclaration: Boolean = true,
)

@Serializable
data class KastCallersQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val direction: WrapperCallDirection = WrapperCallDirection.INCOMING,
    val depth: Int = 2,
    val maxTotalCalls: Int? = null,
    val maxChildrenPerNode: Int? = null,
    val timeoutMillis: Int? = null,
)

@Serializable
data class KastDiagnosticsQuery(
    val workspaceRoot: String,
    val filePaths: List<String>,
)

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
data class KastScaffoldQuery(
    val workspaceRoot: String,
    val targetFile: String,
    val targetSymbol: String? = null,
    val mode: WrapperScaffoldMode = WrapperScaffoldMode.IMPLEMENT,
    val kind: WrapperNamedSymbolKind? = null,
)

@Serializable
data class KastWorkspaceFilesQuery(
    val workspaceRoot: String,
    val moduleName: String? = null,
    val includeFiles: Boolean = false,
    val maxFilesPerModule: Int? = null,
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
data class KastFileOutlineQuery(
    val workspaceRoot: String,
    val filePath: String,
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
data class KastWriteAndValidateFailureQuery(
    val type: String? = null,
    val workspaceRoot: String,
    val filePath: String,
)

@Serializable
sealed interface KastWriteAndValidateQuery

@Serializable
@SerialName("CREATE_FILE_REQUEST")
data class KastWriteAndValidateCreateFileQuery(
    val workspaceRoot: String,
    val filePath: String,
) : KastWriteAndValidateQuery

@Serializable
@SerialName("INSERT_AT_OFFSET_REQUEST")
data class KastWriteAndValidateInsertAtOffsetQuery(
    val workspaceRoot: String,
    val filePath: String,
    val offset: Int,
) : KastWriteAndValidateQuery

@Serializable
@SerialName("REPLACE_RANGE_REQUEST")
data class KastWriteAndValidateReplaceRangeQuery(
    val workspaceRoot: String,
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
) : KastWriteAndValidateQuery

@Serializable
data class KastCandidate(
    val line: Int,
    val column: Int,
    val context: String,
)

@Serializable
data class KastScaffoldReferences(
    val locations: List<Location>,
    val count: Int,
    val searchScope: SearchScope? = null,
    val declaration: Symbol? = null,
)

@Serializable
data class KastScaffoldTypeHierarchy(
    val root: TypeHierarchyNode,
    val stats: TypeHierarchyStats,
)

@Serializable
data class KastDiagnosticsSummary(
    val clean: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val errors: List<Diagnostic> = emptyList(),
)

@Serializable
sealed interface KastResolveResponse

@Serializable
@SerialName("RESOLVE_SUCCESS")
data class KastResolveSuccessResponse(
    val ok: Boolean = true,
    val query: KastResolveQuery,
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val candidate: KastCandidate,
    val candidateCount: Int? = null,
    val alternatives: List<String>? = null,
    val context: KastResolveContext? = null,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_FAILURE")
data class KastResolveFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastResolveQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastResolveResponse

@Serializable
sealed interface KastDiscoverResponse

@Serializable
@SerialName("DISCOVER_SUCCESS")
data class KastDiscoverSuccessResponse(
    val ok: Boolean = true,
    val query: KastDiscoverQuery,
    val candidates: List<KastDiscoveryCandidate>,
    val page: PageInfo? = null,
    val logFile: String,
) : KastDiscoverResponse

@Serializable
@SerialName("DISCOVER_FAILURE")
data class KastDiscoverFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastDiscoverQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastDiscoverResponse

@Serializable
sealed interface KastReferencesResponse

@Serializable
@SerialName("REFERENCES_SUCCESS")
data class KastReferencesSuccessResponse(
    val ok: Boolean = true,
    val query: KastReferencesQuery,
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val references: List<Location>,
    val searchScope: SearchScope? = null,
    val declaration: Symbol? = null,
    val candidateCount: Int? = null,
    val alternatives: List<String>? = null,
    val logFile: String,
) : KastReferencesResponse

@Serializable
@SerialName("REFERENCES_FAILURE")
data class KastReferencesFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastReferencesQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastReferencesResponse

@Serializable
sealed interface KastCallersResponse

@Serializable
@SerialName("CALLERS_SUCCESS")
data class KastCallersSuccessResponse(
    val ok: Boolean = true,
    val query: KastCallersQuery,
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val root: CallNode,
    val stats: CallHierarchyStats,
    val candidateCount: Int? = null,
    val alternatives: List<String>? = null,
    val logFile: String,
) : KastCallersResponse

@Serializable
@SerialName("CALLERS_FAILURE")
data class KastCallersFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastCallersQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastCallersResponse

@Serializable
sealed interface KastDiagnosticsResponse

@Serializable
@SerialName("DIAGNOSTICS_SUCCESS")
data class KastDiagnosticsSuccessResponse(
    val ok: Boolean = true,
    val query: KastDiagnosticsQuery,
    val clean: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val diagnostics: List<Diagnostic>,
    val logFile: String,
) : KastDiagnosticsResponse

@Serializable
@SerialName("DIAGNOSTICS_FAILURE")
data class KastDiagnosticsFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastDiagnosticsQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastDiagnosticsResponse

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

@Serializable
sealed interface KastWorkspaceFilesResponse

@Serializable
@SerialName("WORKSPACE_FILES_SUCCESS")
data class KastWorkspaceFilesSuccessResponse(
    val ok: Boolean = true,
    val query: KastWorkspaceFilesQuery,
    val modules: List<WorkspaceModule>,
    val schemaVersion: Int,
    val logFile: String,
) : KastWorkspaceFilesResponse

@Serializable
@SerialName("WORKSPACE_FILES_FAILURE")
data class KastWorkspaceFilesFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWorkspaceFilesQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastWorkspaceFilesResponse

@Serializable
sealed interface KastFileOutlineResponse

@Serializable
@SerialName("FILE_OUTLINE_SUCCESS")
data class KastFileOutlineSuccessResponse(
    val ok: Boolean = true,
    val query: KastFileOutlineQuery,
    val symbols: List<OutlineSymbol>,
    val logFile: String,
) : KastFileOutlineResponse

@Serializable
@SerialName("FILE_OUTLINE_FAILURE")
data class KastFileOutlineFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastFileOutlineQuery,
    val logFile: String,
) : KastFileOutlineResponse

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

@Serializable
sealed interface KastWriteAndValidateResponse

@Serializable
@SerialName("WRITE_AND_VALIDATE_SUCCESS")
data class KastWriteAndValidateSuccessResponse(
    val ok: Boolean,
    val query: KastWriteAndValidateQuery,
    val appliedEdits: Int,
    val importChanges: Int,
    val diagnostics: KastDiagnosticsSummary,
    val message: String? = null,
    val logFile: String,
) : KastWriteAndValidateResponse

@Serializable
@SerialName("WRITE_AND_VALIDATE_FAILURE")
data class KastWriteAndValidateFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWriteAndValidateFailureQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastWriteAndValidateResponse
