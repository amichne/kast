package io.github.amichne.kast.api.wrapper

import io.github.amichne.kast.api.contract.*
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
enum class WrapperMetric {
    @SerialName("fan-in")
    FAN_IN,

    @SerialName("fan-out")
    FAN_OUT,

    @SerialName("coupling")
    COUPLING,

    @SerialName("dead-code")
    DEAD_CODE,

    @SerialName("impact")
    IMPACT,
}

@Serializable
data class KastMetricsRequest(
    val workspaceRoot: String? = null,
    val metric: WrapperMetric,
    val limit: Int = 50,
    val symbol: String? = null,
    val depth: Int = 3,
)

@Serializable
data class KastMetricsQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    val metric: WrapperMetric,
    val limit: Int = 50,
    val symbol: String? = null,
    val depth: Int = 3,
)

@Serializable
sealed interface KastMetricsResponse

@Serializable
@SerialName("METRICS_SUCCESS")
data class KastMetricsSuccessResponse(
    val ok: Boolean = true,
    val query: KastMetricsQuery,
    val results: kotlinx.serialization.json.JsonElement,
    @SerialName("log_file")
    val logFile: String,
) : KastMetricsResponse

@Serializable
@SerialName("METRICS_FAILURE")
data class KastMetricsFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastMetricsQuery,
    @SerialName("log_file")
    val logFile: String,
) : KastMetricsResponse

@Serializable
data class KastResolveRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
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
    @SerialName("workspace_root")
    val workspaceRoot: String,
    val symbol: String,
    @SerialName("file_hint")
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    @SerialName("containing_type")
    val containingType: String? = null,
)

@Serializable
data class KastReferencesQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    val symbol: String,
    @SerialName("file_hint")
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    @SerialName("containing_type")
    val containingType: String? = null,
    @SerialName("include_declaration")
    val includeDeclaration: Boolean = true,
)

@Serializable
data class KastCallersQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    val symbol: String,
    @SerialName("file_hint")
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    @SerialName("containing_type")
    val containingType: String? = null,
    val direction: WrapperCallDirection = WrapperCallDirection.INCOMING,
    val depth: Int = 2,
    @SerialName("max_total_calls")
    val maxTotalCalls: Int? = null,
    @SerialName("max_children_per_node")
    val maxChildrenPerNode: Int? = null,
    @SerialName("timeout_millis")
    val timeoutMillis: Int? = null,
)

@Serializable
data class KastDiagnosticsQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("file_paths")
    val filePaths: List<String>,
)

@Serializable
data class KastRenameFailureQuery(
    val type: String? = null,
    @SerialName("workspace_root")
    val workspaceRoot: String,
    val symbol: String? = null,
    @SerialName("file_hint")
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    @SerialName("containing_type")
    val containingType: String? = null,
    @SerialName("file_path")
    val filePath: String? = null,
    val offset: Int? = null,
    @SerialName("new_name")
    val newName: String,
)

@Serializable
sealed interface KastRenameQuery

@Serializable
@SerialName("RENAME_BY_SYMBOL_REQUEST")
data class KastRenameBySymbolQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    val symbol: String,
    @SerialName("new_name")
    val newName: String,
    @SerialName("file_hint")
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    @SerialName("containing_type")
    val containingType: String? = null,
    @SerialName("file_path")
    val filePath: String,
    val offset: Int,
) : KastRenameQuery

@Serializable
@SerialName("RENAME_BY_OFFSET_REQUEST")
data class KastRenameByOffsetQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("file_path")
    val filePath: String,
    val offset: Int,
    @SerialName("new_name")
    val newName: String,
) : KastRenameQuery

@Serializable
data class KastScaffoldQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("target_file")
    val targetFile: String,
    @SerialName("target_symbol")
    val targetSymbol: String? = null,
    val mode: WrapperScaffoldMode = WrapperScaffoldMode.IMPLEMENT,
    val kind: WrapperNamedSymbolKind? = null,
)

@Serializable
data class KastWorkspaceFilesQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("module_name")
    val moduleName: String? = null,
    @SerialName("include_files")
    val includeFiles: Boolean = false,
)

@Serializable
data class KastWriteAndValidateFailureQuery(
    val type: String? = null,
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("file_path")
    val filePath: String,
)

@Serializable
sealed interface KastWriteAndValidateQuery

@Serializable
@SerialName("CREATE_FILE_REQUEST")
data class KastWriteAndValidateCreateFileQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("file_path")
    val filePath: String,
) : KastWriteAndValidateQuery

@Serializable
@SerialName("INSERT_AT_OFFSET_REQUEST")
data class KastWriteAndValidateInsertAtOffsetQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("file_path")
    val filePath: String,
    val offset: Int,
) : KastWriteAndValidateQuery

@Serializable
@SerialName("REPLACE_RANGE_REQUEST")
data class KastWriteAndValidateReplaceRangeQuery(
    @SerialName("workspace_root")
    val workspaceRoot: String,
    @SerialName("file_path")
    val filePath: String,
    @SerialName("start_offset")
    val startOffset: Int,
    @SerialName("end_offset")
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
    @SerialName("search_scope")
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
    @SerialName("error_count")
    val errorCount: Int,
    @SerialName("warning_count")
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
    @SerialName("file_path")
    val filePath: String,
    val offset: Int,
    val candidate: KastCandidate,
    @SerialName("log_file")
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_FAILURE")
data class KastResolveFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastResolveQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
    val errorText: String? = null,
) : KastResolveResponse

@Serializable
sealed interface KastReferencesResponse

@Serializable
@SerialName("REFERENCES_SUCCESS")
data class KastReferencesSuccessResponse(
    val ok: Boolean = true,
    val query: KastReferencesQuery,
    val symbol: Symbol,
    @SerialName("file_path")
    val filePath: String,
    val offset: Int,
    val references: List<Location>,
    @SerialName("search_scope")
    val searchScope: SearchScope? = null,
    val declaration: Symbol? = null,
    @SerialName("log_file")
    val logFile: String,
) : KastReferencesResponse

@Serializable
@SerialName("REFERENCES_FAILURE")
data class KastReferencesFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastReferencesQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
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
    @SerialName("file_path")
    val filePath: String,
    val offset: Int,
    val root: CallNode,
    val stats: CallHierarchyStats,
    @SerialName("log_file")
    val logFile: String,
) : KastCallersResponse

@Serializable
@SerialName("CALLERS_FAILURE")
data class KastCallersFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastCallersQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
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
    @SerialName("error_count")
    val errorCount: Int,
    @SerialName("warning_count")
    val warningCount: Int,
    @SerialName("info_count")
    val infoCount: Int,
    val diagnostics: List<Diagnostic>,
    @SerialName("log_file")
    val logFile: String,
) : KastDiagnosticsResponse

@Serializable
@SerialName("DIAGNOSTICS_FAILURE")
data class KastDiagnosticsFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastDiagnosticsQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
    val errorText: String? = null,
) : KastDiagnosticsResponse

@Serializable
sealed interface KastRenameResponse

@Serializable
@SerialName("RENAME_SUCCESS")
data class KastRenameSuccessResponse(
    val ok: Boolean,
    val query: KastRenameQuery,
    @SerialName("edit_count")
    val editCount: Int,
    @SerialName("affected_files")
    val affectedFiles: List<String>,
    @SerialName("apply_result")
    val applyResult: ApplyEditsResult,
    val diagnostics: KastDiagnosticsSummary,
    @SerialName("log_file")
    val logFile: String,
) : KastRenameResponse

@Serializable
@SerialName("RENAME_FAILURE")
data class KastRenameFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastRenameFailureQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
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
    @SerialName("file_content")
    val fileContent: String? = null,
    val symbol: Symbol? = null,
    val references: KastScaffoldReferences? = null,
    @SerialName("type_hierarchy")
    val typeHierarchy: KastScaffoldTypeHierarchy? = null,
    @SerialName("insertion_point")
    val insertionPoint: SemanticInsertionResult? = null,
    @SerialName("log_file")
    val logFile: String,
) : KastScaffoldResponse

@Serializable
@SerialName("SCAFFOLD_FAILURE")
data class KastScaffoldFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastScaffoldQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
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
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("log_file")
    val logFile: String,
) : KastWorkspaceFilesResponse

@Serializable
@SerialName("WORKSPACE_FILES_FAILURE")
data class KastWorkspaceFilesFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWorkspaceFilesQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
    val errorText: String? = null,
) : KastWorkspaceFilesResponse

@Serializable
sealed interface KastWriteAndValidateResponse

@Serializable
@SerialName("WRITE_AND_VALIDATE_SUCCESS")
data class KastWriteAndValidateSuccessResponse(
    val ok: Boolean,
    val query: KastWriteAndValidateQuery,
    @SerialName("applied_edits")
    val appliedEdits: Int,
    @SerialName("import_changes")
    val importChanges: Int,
    val diagnostics: KastDiagnosticsSummary,
    val message: String? = null,
    @SerialName("log_file")
    val logFile: String,
) : KastWriteAndValidateResponse

@Serializable
@SerialName("WRITE_AND_VALIDATE_FAILURE")
data class KastWriteAndValidateFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWriteAndValidateFailureQuery,
    @SerialName("log_file")
    val logFile: String,
    val error: ApiErrorResponse? = null,
    @SerialName("error_text")
    val errorText: String? = null,
) : KastWriteAndValidateResponse
