package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.docs.OperationDoc

internal fun readOperationDocs(): List<OperationDoc> = listOf(
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
                "The first request captures a generation-bound inventory snapshot and returns its opaque reusable `snapshotToken`.",
                "File pages require that snapshot token and an exact module name. Each opaque `nextPageToken` is single-use and bound to the snapshot, file-kind domain, module, and page size.",
                "Unknown, replayed, mismatched, evicted, or stale snapshot and page handles fail instead of restarting enumeration.",
            ),
            errorCodes = listOf(
                "CAPABILITY_NOT_SUPPORTED",
                "INVALID_WORKSPACE_FILE_CURSOR",
                "STALE_WORKSPACE_INVENTORY",
            ),
        ),
        OperationDoc(
            operationId = "semanticGraph",
            jsonRpcMethod = "raw/semantic-graph",
            summary = "Refresh and persist a compiler-backed Kotlin semantic graph",
            tag = "read",
            capability = "SEMANTIC_GRAPH",
            requestSchema = "SemanticGraphQuery",
            responseSchema = "SemanticGraphResult",
            description = "Refreshes selected Kotlin files through K2 analysis, atomically persists provider-neutral graph facts, and returns coverage plus persisted fact counts.",
            behavioralNotes = listOf(
                "PSI is used only inside the indexer for enumeration and source ranges; no PSI or Analysis API object crosses the contract boundary.",
                "The result is an atomic refresh acknowledgement; enumerate native graph nodes separately with generation-pinned keyset queries.",
                "Compiler-resolved library and JDK targets are omitted and counted in coverage evidence.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED", "VALIDATION_ERROR", "CONFLICT"),
        ),
        OperationDoc(
            operationId = "workspaceFilesContinuation",
            jsonRpcMethod = "raw/workspace-files-continuation",
            summary = "Issue or consume public workspace-file continuation state",
            tag = "read",
            requestSchema = "WorkspaceFilesContinuationQuery",
            responseSchema = "WorkspaceFilesContinuationResult",
            description = "Internal bridge used by the public workspace-files command to keep coherent composition state server-side between result pages.",
            behavioralNotes = listOf(
                "This internal method is not a backend capability and is not a public command surface.",
                "ISSUE stores the supplied typed state and returns a canonical random UUID handle; CONSUME is single-use and returns only a non-owning projection.",
                "The exact workspace root, backend, normalized query, projection, and limit must match when consuming a handle.",
            ),
            errorCodes = listOf("VALIDATION_ERROR", "INVALID_WORKSPACE_FILES_PAGE_TOKEN"),
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
)
