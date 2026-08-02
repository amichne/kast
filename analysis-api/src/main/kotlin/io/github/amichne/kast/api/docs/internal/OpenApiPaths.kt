package io.github.amichne.kast.api.docs.internal

internal fun openApiPaths(): Map<String, Any?> = linkedMapOf(
    // System
    "/rpc/health" to systemMethod(
        operationId = "health",
        summary = "Basic health check",
        method = "health",
        responseSchema = "HealthResponse",
    ),
    "/rpc/runtime-status" to systemMethod(
        operationId = "runtimeStatus",
        summary = "Detailed runtime state including indexing progress",
        method = "runtime/status",
        responseSchema = "RuntimeStatusResponse",
    ),
    "/rpc/runtime-shutdown" to systemMethod(
        operationId = "runtimeShutdown",
        summary = "Request runtime host shutdown after the response is flushed",
        method = "runtime/shutdown",
        responseSchema = "RuntimeLifecycleResponse",
    ),
    "/rpc/runtime-restart" to systemMethod(
        operationId = "runtimeRestart",
        summary = "Request runtime host restart after the response is flushed",
        method = "runtime/restart",
        responseSchema = "RuntimeLifecycleResponse",
    ),
    "/rpc/capabilities" to systemMethod(
        operationId = "capabilities",
        summary = "Advertised read and mutation capabilities",
        method = "capabilities",
        responseSchema = "BackendCapabilities",
    ),

    // Read operations
    "/rpc/raw/resolve" to readMethod(
        operationId = "resolveSymbol",
        summary = "Resolve the symbol at a file position",
        method = "raw/resolve",
        requestSchema = "SymbolQuery",
        responseSchema = "SymbolResult",
        capability = "RESOLVE_SYMBOL",
    ),
    "/rpc/raw/references" to readMethod(
        operationId = "findReferences",
        summary = "Find all references to the symbol at a file position",
        method = "raw/references",
        requestSchema = "ReferencesQuery",
        responseSchema = "ReferencesResult",
        capability = "FIND_REFERENCES",
    ),
    "/rpc/raw/call-hierarchy" to readMethod(
        operationId = "callHierarchy",
        summary = "Expand a bounded incoming or outgoing call tree",
        method = "raw/call-hierarchy",
        requestSchema = "CallHierarchyQuery",
        responseSchema = "CallHierarchyResult",
        capability = "CALL_HIERARCHY",
    ),
    "/rpc/raw/type-hierarchy" to readMethod(
        operationId = "typeHierarchy",
        summary = "Expand supertypes and subtypes from a resolved symbol",
        method = "raw/type-hierarchy",
        requestSchema = "TypeHierarchyQuery",
        responseSchema = "TypeHierarchyResult",
        capability = "TYPE_HIERARCHY",
    ),
    "/rpc/raw/semantic-insertion-point" to readMethod(
        operationId = "semanticInsertionPoint",
        summary = "Find the best insertion point for a new declaration",
        method = "raw/semantic-insertion-point",
        requestSchema = "SemanticInsertionQuery",
        responseSchema = "SemanticInsertionResult",
        capability = "SEMANTIC_INSERTION_POINT",
    ),
    "/rpc/raw/diagnostics" to readMethod(
        operationId = "diagnostics",
        summary = "Run compilation diagnostics for one or more files",
        method = "raw/diagnostics",
        requestSchema = "DiagnosticsQuery",
        responseSchema = "DiagnosticsResult",
        capability = "DIAGNOSTICS",
    ),
    "/rpc/raw/file-outline" to readMethod(
        operationId = "fileOutline",
        summary = "Get a hierarchical symbol outline for a single file",
        method = "raw/file-outline",
        requestSchema = "FileOutlineQuery",
        responseSchema = "FileOutlineResult",
        capability = "FILE_OUTLINE",
    ),
    "/rpc/raw/workspace-symbol" to readMethod(
        operationId = "workspaceSymbolSearch",
        summary = "Search the workspace for symbols by name pattern",
        method = "raw/workspace-symbol",
        requestSchema = "WorkspaceSymbolQuery",
        responseSchema = "WorkspaceSymbolResult",
        capability = "WORKSPACE_SYMBOL_SEARCH",
    ),
    "/rpc/raw/workspace-search" to readMethod(
        operationId = "workspaceSearch",
        summary = "Search workspace file contents for text patterns",
        method = "raw/workspace-search",
        requestSchema = "WorkspaceSearchQuery",
        responseSchema = "WorkspaceSearchResult",
        capability = "WORKSPACE_SEARCH",
    ),
    "/rpc/raw/workspace-files" to readMethod(
        operationId = "workspaceFiles",
        summary = "List workspace modules and optional source files",
        method = "raw/workspace-files",
        requestSchema = "WorkspaceFilesQuery",
        responseSchema = "WorkspaceFilesResult",
        capability = "WORKSPACE_FILES",
    ),
    "/rpc/raw/semantic-graph" to readMethod(
        operationId = "semanticGraph",
        summary = "Export a compiler-backed Kotlin semantic graph page",
        method = "raw/semantic-graph",
        requestSchema = "SemanticGraphQuery",
        responseSchema = "SemanticGraphResult",
        capability = "SEMANTIC_GRAPH",
    ),
    "/rpc/raw/workspace-files-continuation" to internalReadMethod(
        operationId = "workspaceFilesContinuation",
        summary = "Issue or consume server-held public workspace-file continuation state",
        method = "raw/workspace-files-continuation",
        requestSchema = "WorkspaceFilesContinuationQuery",
        responseSchema = "WorkspaceFilesContinuationResult",
    ),
    "/rpc/raw/implementations" to readMethod(
        operationId = "implementations",
        summary = "Find concrete implementations and subclasses for a declaration",
        method = "raw/implementations",
        requestSchema = "ImplementationsQuery",
        responseSchema = "ImplementationsResult",
        capability = "IMPLEMENTATIONS",
    ),
    "/rpc/raw/code-actions" to readMethod(
        operationId = "codeActions",
        summary = "Return available code actions at a file position",
        method = "raw/code-actions",
        requestSchema = "CodeActionsQuery",
        responseSchema = "CodeActionsResult",
        capability = "CODE_ACTIONS",
    ),
    "/rpc/raw/completions" to readMethod(
        operationId = "completions",
        summary = "Return completion candidates available at a file position",
        method = "raw/completions",
        requestSchema = "CompletionsQuery",
        responseSchema = "CompletionsResult",
        capability = "COMPLETIONS",
    ),

    // Mutation operations
    "/rpc/raw/rename" to mutationMethod(
        operationId = "rename",
        summary = "Plan a symbol rename (dry-run by default)",
        method = "raw/rename",
        requestSchema = "RenameQuery",
        responseSchema = "RenameResult",
        capability = "RENAME",
    ),
    "/rpc/raw/optimize-imports" to mutationMethod(
        operationId = "optimizeImports",
        summary = "Optimize imports for one or more files",
        method = "raw/optimize-imports",
        requestSchema = "ImportOptimizeQuery",
        responseSchema = "ImportOptimizeResult",
        capability = "OPTIMIZE_IMPORTS",
    ),
    "/rpc/raw/apply-edits" to mutationMethod(
        operationId = "applyEdits",
        summary = "Apply a prepared edit plan with file-hash conflict detection",
        method = "raw/apply-edits",
        requestSchema = "ApplyEditsQuery",
        responseSchema = "ApplyEditsResult",
        capability = "APPLY_EDITS",
        extraExtensions = mapOf(
            "x-kast-conditional-capability" to "FILE_OPERATIONS — required when fileOperations is non-empty",
        ),
    ),
    "/rpc/raw/workspace-refresh" to mutationMethod(
        operationId = "refreshWorkspace",
        summary = "Force a targeted or full workspace state refresh",
        method = "raw/workspace-refresh",
        requestSchema = "RefreshQuery",
        responseSchema = "RefreshResult",
        capability = "REFRESH_WORKSPACE",
    ),
)

private fun systemMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String? = null,
    responseSchema: String,
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("system"),
        "x-jsonrpc-method" to method,
    ).also { operation ->
        if (requestSchema != null) {
            operation["requestBody"] = linkedMapOf(
                "required" to true,
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(requestSchema),
                    ),
                ),
            )
        }
        operation["responses"] = linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        )
    },
)

private fun readMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String,
    responseSchema: String,
    capability: String,
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("read"),
        "x-jsonrpc-method" to method,
        "x-kast-required-capability" to capability,
        "requestBody" to linkedMapOf(
            "required" to true,
            "content" to linkedMapOf(
                "application/json" to linkedMapOf(
                    "schema" to ref(requestSchema),
                ),
            ),
        ),
        "responses" to linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        ),
    ),
)

private fun internalReadMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String,
    responseSchema: String,
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("read"),
        "x-jsonrpc-method" to method,
        "requestBody" to linkedMapOf(
            "required" to true,
            "content" to linkedMapOf(
                "application/json" to linkedMapOf(
                    "schema" to ref(requestSchema),
                ),
            ),
        ),
        "responses" to linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        ),
    ),
)

private fun mutationMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String,
    responseSchema: String,
    capability: String,
    extraExtensions: Map<String, String> = emptyMap(),
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("mutation"),
        "x-jsonrpc-method" to method,
        "x-kast-required-capability" to capability,
    ).also { op ->
        extraExtensions.forEach { (k, v) -> op[k] = v }
        op["requestBody"] = linkedMapOf(
            "required" to true,
            "content" to linkedMapOf(
                "application/json" to linkedMapOf(
                    "schema" to ref(requestSchema),
                ),
            ),
        )
        op["responses"] = linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        )
    },
)

private fun errorResponse(): Map<String, Any?> = linkedMapOf(
    "description" to "JSON-RPC error response",
    "content" to linkedMapOf(
        "application/json" to linkedMapOf(
            "schema" to ref("JsonRpcErrorResponse"),
        ),
    ),
)

private fun ref(name: String): Map<String, Any?> = linkedMapOf("\$ref" to "#/components/schemas/$name")
