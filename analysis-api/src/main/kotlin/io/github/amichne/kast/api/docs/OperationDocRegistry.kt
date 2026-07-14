package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.*

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
        ),
        OperationDoc(
            operationId = "runtimeShutdown",
            jsonRpcMethod = "runtime/shutdown",
            summary = "Request runtime host shutdown after the response is flushed",
            tag = "system",
            responseSchema = "RuntimeLifecycleResponse",
            description = "Requests that the runtime host shut down the current backend " +
                "after returning a JSON-RPC response. IDEA hosts stop the plugin backend " +
                "server and indexer without killing the IDE process; headless daemon " +
                "process lifecycle is handled by the top-level `kast stop` command.",
            behavioralNotes = listOf(
                "The response is flushed before the lifecycle action runs, so callers can observe an accepted request.",
                "Hosts without lifecycle support return a capability-not-supported JSON-RPC error.",
                "Prefer the top-level `kast stop` command for operator workflows; it handles stale descriptors and backend-specific cleanup.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "runtimeRestart",
            jsonRpcMethod = "runtime/restart",
            summary = "Request runtime host restart after the response is flushed",
            tag = "system",
            responseSchema = "RuntimeLifecycleResponse",
            description = "Requests that the runtime host rebuild the current backend " +
                "after returning a JSON-RPC response. IDEA hosts restart the plugin " +
                "backend server and indexer in the open IDE; headless daemon rebuilds " +
                "are handled by the top-level `kast restart` command.",
            behavioralNotes = listOf(
                "The response is flushed before the lifecycle action runs, so callers can observe an accepted request.",
                "Hosts without lifecycle support return a capability-not-supported JSON-RPC error.",
                "Prefer the top-level `kast restart` command for operator workflows; it combines the host lifecycle request with readiness waiting.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
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
        ),

        // Read operations
        OperationDoc(
            operationId = "resolveSymbol",
            jsonRpcMethod = "raw/resolve",
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
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "findReferences",
            jsonRpcMethod = "raw/references",
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
                "Large result sets are paginated; check the `page` field for continuation. " +
                    "Tokens are opaque, one-use handles for server-held state bound to the " +
                    "workspace, query options, evidence source, and source generation.",
                "Unknown, replayed, mismatched, evicted, or stale continuation tokens fail " +
                    "with a typed conflict instead of restarting or reinterpreting traversal.",
            ),
            errorCodes = listOf("NOT_FOUND", "CONFLICT"),
        ),
        OperationDoc(
            operationId = "callHierarchy",
            jsonRpcMethod = "raw/call-hierarchy",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "typeHierarchy",
            jsonRpcMethod = "raw/type-hierarchy",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "semanticInsertionPoint",
            jsonRpcMethod = "raw/semantic-insertion-point",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "diagnostics",
            jsonRpcMethod = "raw/diagnostics",
            summary = "Run compilation diagnostics for files",
            tag = "read",
            capability = "DIAGNOSTICS",
            requestSchema = "DiagnosticsQuery",
            responseSchema = "DiagnosticsResult",
            description = "Runs compilation diagnostics for one or more files, " +
                "returning errors, warnings, and informational messages with " +
                "precise source locations.",
            behavioralNotes = listOf(
                "Pass one or more absolute file paths. The daemon analyzes each file, " +
                    "returns an ordered bounded page, and reports exact full-set severity counts and cardinality.",
                "The first page captures a server-held diagnostic snapshot. Its opaque, " +
                    "one-use continuation token is bound to the ordered files, limit, and Kotlin PSI generation.",
                "Continuation pages reuse that snapshot without refreshing or recomputing. " +
                    "Unknown, replayed, mismatched, evicted, or stale tokens fail with a typed conflict.",
                "Diagnostics reflect the current daemon state. Before the first page, a successful focused " +
                    "`raw/workspace-refresh` is a semantic-admission barrier for externally modified files; " +
                    "a refresh that changes Kotlin PSI invalidates earlier continuations.",
            ),
            errorCodes = listOf("NOT_FOUND", "CONFLICT"),
        ),
        OperationDoc(
            operationId = "fileOutline",
            jsonRpcMethod = "raw/file-outline",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "workspaceSymbolSearch",
            jsonRpcMethod = "raw/workspace-symbol",
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
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "workspaceSearch",
            jsonRpcMethod = "raw/workspace-search",
            summary = "Search workspace file contents for text patterns",
            tag = "read",
            capability = "WORKSPACE_SEARCH",
            requestSchema = "WorkspaceSearchQuery",
            responseSchema = "WorkspaceSearchResult",
            description = "Searches workspace file contents for literal text or regex patterns.\n" +
                "Use this for Kotlin comments, string literals, and other non-symbol\ncontent.",
            behavioralNotes = listOf(
                "Use `fileGlob` to narrow the search to specific source sets or\nfile types.",
                "Set `regex` to true for regular expression patterns.",
                "`caseSensitive` applies only to the content matching step.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "workspaceFiles",
            jsonRpcMethod = "raw/workspace-files",
            summary = "List workspace modules and optional source files",
            tag = "read",
            capability = "WORKSPACE_FILES",
            requestSchema = "WorkspaceFilesQuery",
            responseSchema = "WorkspaceFilesResult",
            description = "Lists workspace modules and optionally source files. Use this " +
                "as a secondary scope check after bounded symbol or text queries.",
            behavioralNotes = listOf(
                "Leave `includeFiles` false for the bounded module summary.",
                "When file paths are required, filter by `moduleName` and set a small `maxFilesPerModule`.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "implementations",
            jsonRpcMethod = "raw/implementations",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "codeActions",
            jsonRpcMethod = "raw/code-actions",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "completions",
            jsonRpcMethod = "raw/completions",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),

        // Mutation operations
        OperationDoc(
            operationId = "rename",
            jsonRpcMethod = "raw/rename",
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
                "Pair with `raw/apply-edits` to execute the rename after review.",
            ),
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "optimizeImports",
            jsonRpcMethod = "raw/optimize-imports",
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
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "applyEdits",
            jsonRpcMethod = "raw/apply-edits",
            summary = "Apply a prepared edit plan with conflict detection",
            tag = "mutation",
            capability = "APPLY_EDITS",
            requestSchema = "ApplyEditsQuery",
            responseSchema = "ApplyEditsResult",
            description = "Applies a prepared edit plan with file-hash conflict " +
                "detection. Pass the edits and hashes returned by a prior " +
                "`raw/rename` or other planning operation.",
            behavioralNotes = listOf(
                "File hashes are compared before writing. If a file changed since " +
                    "the edits were planned, the operation fails with a conflict error.",
                "Supports optional `fileOperations` for creating or deleting files.",
            ),
            errorCodes = listOf("CONFLICT", "VALIDATION_ERROR"),
        ),
        OperationDoc(
            operationId = "refreshWorkspace",
            jsonRpcMethod = "raw/workspace-refresh",
            summary = "Force a targeted or full workspace state refresh",
            tag = "mutation",
            capability = "REFRESH_WORKSPACE",
            requestSchema = "RefreshQuery",
            responseSchema = "RefreshResult",
            description = "Refreshes the daemon after external file modifications. " +
                "A successful focused refresh proves each existing requested Kotlin " +
                "path is immediately available for semantic analysis.",
            behavioralNotes = listOf(
                "Pass specific file paths for a targeted refresh, or omit for a " +
                    "full workspace refresh.",
                "Each focused path separately reports filesystem discovery, source-module " +
                    "ownership, index admission, and analysis availability.",
                "Pending admission is retried for a bounded interval. The result reports " +
                    "attempt and elapsed-time progress and fails closed if admission remains incomplete.",
                "Removed paths are terminal refresh results and do not count as skipped analysis.",
            ),
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
