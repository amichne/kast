package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.*

/**
 * Per-operation editorial metadata used by [DocsDocument] to enrich
 * generated documentation beyond what the schema models carry.
 *
 * Each entry corresponds to one JSON-RPC method dispatched by the analysis
 * daemon. The [operationId] matches the value used in
 * [OpenApiDocument.writePaths].
 */
data class OperationDoc(
    // Structural metadata (mirrors OpenApiDocument.writePaths)
    val operationId: String,
    val jsonRpcMethod: String,
    val summary: String,
    val tag: String,
    val capability: String? = null,
    val requestSchema: String? = null,
    val responseSchema: String,

    // Editorial metadata
    val description: String,
    val behavioralNotes: List<String> = emptyList(),
    val cliExample: String = "",
    val exampleFixtureId: String = operationId,
    val errorCodes: List<String> = emptyList(),
)

/**
 * Registry of editorial documentation for every JSON-RPC operation.
 *
 * This object is the single source of truth for prose that accompanies each
 * operation in generated docs. It is intentionally separate from the OpenAPI
 * spec generator so editorial content can be refined without touching the
 * schema pipeline.
 */
object OperationDocRegistry {

    private val entries: Map<String, OperationDoc> = listOf(
        // System operations
        OperationDoc(
            operationId = "health",
            jsonRpcMethod = "health",
            summary = "Basic health check",
            tag = "system",
            responseSchema = "HealthResponse",
            description = "Returns a lightweight health check confirming the daemon " +
                "is responsive. Use this before dispatching heavier queries.",
            cliExample = "kast health --workspace-root=/path/to/project",
        ),
        OperationDoc(
            operationId = "runtimeStatus",
            jsonRpcMethod = "runtime/status",
            summary = "Detailed runtime state including indexing progress",
            tag = "system",
            responseSchema = "RuntimeStatusResponse",
            description = "Returns the full runtime state including indexing progress, " +
                "backend identity, and workspace root. Use this to verify readiness " +
                "before running analysis commands.",
            cliExample = "kast workspace status --workspace-root=/path/to/project",
        ),
        OperationDoc(
            operationId = "capabilities",
            jsonRpcMethod = "capabilities",
            summary = "Advertised read and mutation capabilities",
            tag = "system",
            responseSchema = "BackendCapabilities",
            description = "Lists every read and mutation capability the current backend " +
                "advertises, along with server limits. Query this before calling an " +
                "operation to confirm it is available.",
            cliExample = "kast capabilities --workspace-root=/path/to/project",
        ),

        // Read operations
        OperationDoc(
            operationId = "resolveSymbol",
            jsonRpcMethod = "symbol/resolve",
            summary = "Resolve the symbol at a file position",
            tag = "read",
            capability = "RESOLVE_SYMBOL",
            requestSchema = "SymbolQuery",
            responseSchema = "SymbolResult",
            description = "Resolves the symbol at a file position, returning its fully " +
                "qualified name, kind, location, and optional metadata such as type " +
                "information and documentation.",
            behavioralNotes = listOf(
                "The position must be an absolute file path with a zero-based byte offset.",
                "If the offset does not land on a symbol, the daemon returns a NOT_FOUND error.",
                "Optional fields like `declarationScope` and `documentation` are only " +
                    "populated when the corresponding query flags are set.",
            ),
            cliExample = "kast resolve --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42",
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "findReferences",
            jsonRpcMethod = "references",
            summary = "Find all references to the symbol at a file position",
            tag = "read",
            capability = "FIND_REFERENCES",
            requestSchema = "ReferencesQuery",
            responseSchema = "ReferencesResult",
            description = "Finds all references to the symbol at a file position across " +
                "the workspace. Optionally includes the declaration itself.",
            behavioralNotes = listOf(
                "Results are workspace-scoped — references outside the current workspace " +
                    "are not returned.",
                "Set `includeDeclaration` to true to include the symbol's declaration " +
                    "in the result alongside usage sites.",
                "Large result sets are paginated; check the `page` field for continuation.",
            ),
            cliExample = "kast references --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42",
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "callHierarchy",
            jsonRpcMethod = "call-hierarchy",
            summary = "Expand a bounded incoming or outgoing call tree",
            tag = "read",
            capability = "CALL_HIERARCHY",
            requestSchema = "CallHierarchyQuery",
            responseSchema = "CallHierarchyResult",
            description = "Expands a bounded incoming or outgoing call tree from a " +
                "function or method. Use incoming to find callers, outgoing to find callees.",
            behavioralNotes = listOf(
                "Traversal is bounded by `depth`, `maxTotalCalls`, and " +
                    "`maxChildrenPerNode`. The stats object reports whether any " +
                    "bound was reached.",
                "Set `direction` to `INCOMING` for callers or `OUTGOING` for callees.",
                "Cycles are detected and reported via truncation metadata on the " +
                    "affected node.",
            ),
            cliExample = "kast call-hierarchy --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42 " +
                "--direction=INCOMING --depth=2",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "typeHierarchy",
            jsonRpcMethod = "type-hierarchy",
            summary = "Expand supertypes and subtypes from a resolved symbol",
            tag = "read",
            capability = "TYPE_HIERARCHY",
            requestSchema = "TypeHierarchyQuery",
            responseSchema = "TypeHierarchyResult",
            description = "Expands supertypes and subtypes from a resolved symbol. " +
                "Use this to understand inheritance relationships.",
            behavioralNotes = listOf(
                "Set `direction` to `SUPERTYPES`, `SUBTYPES`, or `BOTH`.",
                "Traversal is bounded by `depth` and `maxResults`. The stats object " +
                    "reports whether truncation occurred.",
            ),
            cliExample = "kast type-hierarchy --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42 " +
                "--direction=BOTH",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "semanticInsertionPoint",
            jsonRpcMethod = "semantic-insertion-point",
            summary = "Find the best insertion point for a new declaration",
            tag = "read",
            capability = "SEMANTIC_INSERTION_POINT",
            requestSchema = "SemanticInsertionQuery",
            responseSchema = "SemanticInsertionResult",
            description = "Finds the best insertion point for a new declaration " +
                "relative to a file position. Use this to place generated code " +
                "at a semantically appropriate location.",
            behavioralNotes = listOf(
                "The `target` field controls where the insertion point is computed: " +
                    "class body start/end, file top/bottom, or after imports.",
            ),
            cliExample = "kast insertion-point --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42 " +
                "--target=AFTER_IMPORTS",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "diagnostics",
            jsonRpcMethod = "diagnostics",
            summary = "Run compilation diagnostics for files",
            tag = "read",
            capability = "DIAGNOSTICS",
            requestSchema = "DiagnosticsQuery",
            responseSchema = "DiagnosticsResult",
            description = "Runs compilation diagnostics for one or more files, " +
                "returning errors, warnings, and informational messages with " +
                "precise source locations.",
            behavioralNotes = listOf(
                "Pass one or more absolute file paths. The daemon analyzes each " +
                    "file and returns all diagnostics sorted by location.",
                "Diagnostics reflect the current daemon state. Call `workspace/refresh` " +
                    "first if files were modified outside the daemon.",
            ),
            cliExample = "kast diagnostics --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt",
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "fileOutline",
            jsonRpcMethod = "file-outline",
            summary = "Get a hierarchical symbol outline for a file",
            tag = "read",
            capability = "FILE_OUTLINE",
            requestSchema = "FileOutlineQuery",
            responseSchema = "FileOutlineResult",
            description = "Returns a hierarchical symbol outline for a single file, " +
                "listing all named declarations and their nesting.",
            behavioralNotes = listOf(
                "The outline includes classes, functions, properties, and other " +
                    "named declarations with their fully qualified names.",
            ),
            cliExample = "kast outline --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "workspaceSymbolSearch",
            jsonRpcMethod = "workspace-symbol",
            summary = "Search the workspace for symbols by name pattern",
            tag = "read",
            capability = "WORKSPACE_SYMBOL_SEARCH",
            requestSchema = "WorkspaceSymbolQuery",
            responseSchema = "WorkspaceSymbolResult",
            description = "Searches the entire workspace for symbols matching a name " +
                "pattern. Supports substring matching and optional regex.",
            behavioralNotes = listOf(
                "The search is case-insensitive by default for substring matching.",
                "Set `regex` to true for regular expression patterns.",
                "Results are bounded by `maxResults`. Set `kind` to filter by symbol type.",
            ),
            cliExample = "kast workspace-symbol --workspace-root=/path/to/project " +
                "--pattern=UserService",
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "workspaceFiles",
            jsonRpcMethod = "workspace/files",
            summary = "List workspace modules and source files",
            tag = "read",
            capability = "WORKSPACE_FILES",
            requestSchema = "WorkspaceFilesQuery",
            responseSchema = "WorkspaceFilesResult",
            description = "Lists workspace modules and their source files. Use this " +
                "to discover the project structure visible to the daemon.",
            behavioralNotes = listOf(
                "Set `includeFiles` to true to include individual file paths per module.",
                "Filter by `moduleName` to inspect a single module.",
            ),
            cliExample = "kast workspace-files --workspace-root=/path/to/project",
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "implementations",
            jsonRpcMethod = "implementations",
            summary = "Find concrete implementations and subclasses for a declaration",
            tag = "read",
            capability = "IMPLEMENTATIONS",
            requestSchema = "ImplementationsQuery",
            responseSchema = "ImplementationsResult",
            description = "Finds concrete implementations and subclasses for an " +
                "interface or abstract class declaration.",
            behavioralNotes = listOf(
                "The position must point to an interface, abstract class, or open class.",
                "Results include the `exhaustive` flag indicating whether all " +
                    "implementations were found within `maxResults`.",
            ),
            cliExample = "kast implementations --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "codeActions",
            jsonRpcMethod = "code-actions",
            summary = "Return available code actions at a file position",
            tag = "read",
            capability = "CODE_ACTIONS",
            requestSchema = "CodeActionsQuery",
            responseSchema = "CodeActionsResult",
            description = "Returns available code actions at a file position, such as " +
                "quick fixes and refactoring suggestions.",
            behavioralNotes = listOf(
                "Code actions are context-dependent and may return an empty list " +
                    "when no actions are applicable.",
            ),
            cliExample = "kast code-actions --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "completions",
            jsonRpcMethod = "completions",
            summary = "Return completion candidates available at a file position",
            tag = "read",
            capability = "COMPLETIONS",
            requestSchema = "CompletionsQuery",
            responseSchema = "CompletionsResult",
            description = "Returns completion candidates available at a file position. " +
                "Use this to discover what symbols, keywords, or snippets the " +
                "compiler suggests.",
            behavioralNotes = listOf(
                "Results are bounded by `maxResults`. The `exhaustive` flag indicates " +
                    "whether all candidates were returned.",
                "Use `kindFilter` to restrict results to specific symbol kinds.",
            ),
            cliExample = "kast completions --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),

        // Mutation operations
        OperationDoc(
            operationId = "rename",
            jsonRpcMethod = "rename",
            summary = "Plan a symbol rename (dry-run by default)",
            tag = "mutation",
            capability = "RENAME",
            requestSchema = "RenameQuery",
            responseSchema = "RenameResult",
            description = "Plans a symbol rename by computing all text edits needed " +
                "across the workspace. This is a dry-run by default — it returns " +
                "edits without applying them.",
            behavioralNotes = listOf(
                "The result includes file hashes for conflict detection when " +
                    "applying edits later.",
                "Pair with `edits/apply` to execute the rename after review.",
            ),
            cliExample = "kast rename --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt --offset=42 " +
                "--new-name=updatedName",
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "optimizeImports",
            jsonRpcMethod = "imports/optimize",
            summary = "Optimize imports for one or more files",
            tag = "mutation",
            capability = "OPTIMIZE_IMPORTS",
            requestSchema = "ImportOptimizeQuery",
            responseSchema = "ImportOptimizeResult",
            description = "Optimizes imports for one or more files, removing unused " +
                "imports and sorting the remainder.",
            behavioralNotes = listOf(
                "Returns the computed edits and file hashes. The daemon applies " +
                    "changes directly.",
            ),
            cliExample = "kast optimize-imports --workspace-root=/path/to/project " +
                "--file=/path/to/project/src/main/kotlin/Example.kt",
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "applyEdits",
            jsonRpcMethod = "edits/apply",
            summary = "Apply a prepared edit plan with conflict detection",
            tag = "mutation",
            capability = "APPLY_EDITS",
            requestSchema = "ApplyEditsQuery",
            responseSchema = "ApplyEditsResult",
            description = "Applies a prepared edit plan with file-hash conflict " +
                "detection. Pass the edits and hashes returned by a prior " +
                "`rename` or other planning operation.",
            behavioralNotes = listOf(
                "File hashes are compared before writing. If a file changed since " +
                    "the edits were planned, the operation fails with a conflict error.",
                "Supports optional `fileOperations` for creating or deleting files.",
            ),
            cliExample = "kast apply-edits --workspace-root=/path/to/project " +
                "--edits-json='{...}'",
            errorCodes = listOf("CONFLICT", "VALIDATION_ERROR"),
        ),
        OperationDoc(
            operationId = "refreshWorkspace",
            jsonRpcMethod = "workspace/refresh",
            summary = "Force a targeted or full workspace state refresh",
            tag = "mutation",
            capability = "REFRESH_WORKSPACE",            requestSchema = "RefreshQuery",
            responseSchema = "RefreshResult",
            description = "Forces the daemon to refresh its workspace state. Use this " +
                "after external file modifications to ensure the daemon's view " +
                "is current.",
            behavioralNotes = listOf(
                "Pass specific file paths for a targeted refresh, or omit for a " +
                    "full workspace refresh.",
                "The result reports which files were refreshed and which were removed.",
            ),
            cliExample = "kast workspace refresh --workspace-root=/path/to/project",
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
    ).associateBy { it.operationId }

    /** Returns the [OperationDoc] for the given [operationId], or null. */
    fun get(operationId: String): OperationDoc? = entries[operationId]

    /** Returns all registered operation docs. */
    fun all(): Collection<OperationDoc> = entries.values

    /** Returns all registered operation IDs. */
    fun operationIds(): Set<String> = entries.keys
}
