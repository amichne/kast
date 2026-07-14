---
title: Capabilities
hide:
    - navigation
    - toc
---

# Capabilities

Every operation the Kast analysis daemon supports, organized by
category. Expand any operation to see its input and output schemas.

=== "System operations"

    !!! abstract "At a glance"

        5 operations for health checks, runtime status, host lifecycle, and capability discovery. No capability gating required.

    ??? info "health — Basic health check"

        === "Input"

            _No parameters._
        === "Output: HealthResponse"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin status: String` :material-information-outline:{ title="Default: &quot;ok&quot;" } | Health status string, always "ok" when the daemon is responsive. |
            | `#!kotlin backendName: String` | Identifier of the analysis backend (e.g. "headless" or "idea"). |
            | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
            | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "runtime/status — Detailed runtime state including indexing progress"

        === "Input"

            _No parameters._
        === "Output: RuntimeStatusResponse"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin state: RuntimeState` | Current runtime state: STARTING, INDEXING, READY, or DEGRADED. |
            | `#!kotlin healthy: Boolean` | True when the daemon is responsive and not in an error state. |
            | `#!kotlin active: Boolean` | True when the daemon has an active workspace session. |
            | `#!kotlin indexing: Boolean` | True when the daemon is currently indexing the workspace. |
            | `#!kotlin backendName: String` | Identifier of the analysis backend. |
            | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
            | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
            | `#!kotlin message: String?` | Human-readable status message with additional context. |
            | `#!kotlin warnings: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Active warning messages about the runtime environment. |
            | `#!kotlin sourceModuleNames: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Names of source modules discovered in the workspace. |
            | `#!kotlin dependentModuleNamesBySourceModuleName: Map<String, List<String>>` :material-information-outline:{ title="Default: emptyMap()" } | Map from source module name to its dependency module names. |
            | `#!kotlin referenceIndexReady: Boolean` :material-information-outline:{ title="Default: false" } | True when the symbol reference index is fully populated. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "runtime/shutdown — Request runtime host shutdown after the response is flushed"

        === "Input"

            _No parameters._
        === "Output: RuntimeLifecycleResponse"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin accepted: Boolean` | Lifecycle action accepted by the runtime host. |
            | `#!kotlin action: RuntimeLifecycleAction` | Requested lifecycle action. |
            | `#!kotlin backendName: String` | Identifier of the analysis backend. |
            | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
            | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
            | `#!kotlin message: String?` | Human-readable lifecycle status message. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "runtime/restart — Request runtime host restart after the response is flushed"

        === "Input"

            _No parameters._
        === "Output: RuntimeLifecycleResponse"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin accepted: Boolean` | Lifecycle action accepted by the runtime host. |
            | `#!kotlin action: RuntimeLifecycleAction` | Requested lifecycle action. |
            | `#!kotlin backendName: String` | Identifier of the analysis backend. |
            | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
            | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
            | `#!kotlin message: String?` | Human-readable lifecycle status message. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "capabilities — Advertised read and mutation capabilities"

        === "Input"

            _No parameters._
        === "Output: BackendCapabilities"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin backendName: String` | Identifier of the analysis backend. |
            | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
            | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
            | `#!kotlin readCapabilities: List<ReadCapability>` | Set of read operations this backend supports. |
            | `#!kotlin mutationCapabilities: List<MutationCapability>` | Set of mutation operations this backend supports. |
            | `#!kotlin limits: ServerLimits` | Server-enforced resource limits. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

=== "Read operations"

    !!! abstract "At a glance"

        13 read-only operations for querying symbols, references, hierarchies, diagnostics, outlines, and completions.

    ??? info "raw/resolve — Resolve the symbol at a file position"

        **Capability** &nbsp;·&nbsp; `RESOLVE_SYMBOL`

        === "Input: SymbolQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol to resolve. |
            | `#!kotlin includeDeclarationScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the declarationScope field on the resolved symbol. |
            | `#!kotlin includeDocumentation: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the documentation field on the resolved symbol. |
        === "Output: SymbolResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin symbol: Symbol` | The resolved symbol at the queried position. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/references — Find all references to the symbol at a file position"

        **Capability** &nbsp;·&nbsp; `FIND_REFERENCES`

        === "Input: ReferencesQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol whose references to find. |
            | `#!kotlin includeDeclaration: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes the symbol's own declaration in the results. |
            | `#!kotlin includeUsageSiteScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes the nearest enclosing declaration scope for each reference usage site. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of reference locations to return. |
            | `#!kotlin pageToken: String?` | Opaque continuation token from the preceding reference page. |
        === "Output: ReferencesResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin declaration: Symbol?` | The resolved declaration symbol, included when `includeDeclaration` was set. |
            | `#!kotlin references: List<Location>` | List of source locations where the symbol is referenced. |
            | `#!kotlin cardinality: ResultCardinality` | Exact or known-minimum cardinality established by bounded reference work. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the search. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/call-hierarchy — Expand a bounded incoming or outgoing call tree"

        **Capability** &nbsp;·&nbsp; `CALL_HIERARCHY`

        === "Input: CallHierarchyQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the function or method to expand. |
            | `#!kotlin direction: CallDirection` | INCOMING for callers or OUTGOING for callees. |
            | `#!kotlin depth: Int` :material-information-outline:{ title="Default: 3" } | Maximum tree depth to traverse. |
            | `#!kotlin maxTotalCalls: Int` :material-information-outline:{ title="Default: 256" } | Maximum total call nodes to return across the entire tree. |
            | `#!kotlin maxChildrenPerNode: Int` :material-information-outline:{ title="Default: 64" } | Maximum direct children per node before truncation. |
            | `#!kotlin timeoutMillis: Long?` | Optional timeout in milliseconds for the traversal. |
        === "Output: CallHierarchyResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin root: CallNode` | Root node of the call hierarchy tree. |
            | `#!kotlin stats: CallHierarchyStats` | Traversal statistics including truncation indicators. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/type-hierarchy — Expand supertypes and subtypes from a resolved symbol"

        **Capability** &nbsp;·&nbsp; `TYPE_HIERARCHY`

        === "Input: TypeHierarchyQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the class or interface to expand. |
            | `#!kotlin direction: TypeHierarchyDirection` :material-information-outline:{ title="Default: BOTH" } | SUPERTYPES, SUBTYPES, or BOTH. |
            | `#!kotlin depth: Int` :material-information-outline:{ title="Default: 3" } | Maximum tree depth to traverse. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 256" } | Maximum total nodes to return. |
        === "Output: TypeHierarchyResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin root: TypeHierarchyNode` | Root node of the type hierarchy tree. |
            | `#!kotlin stats: TypeHierarchyStats` | Traversal statistics including truncation indicators. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/semantic-insertion-point — Find the best insertion point for a new declaration"

        **Capability** &nbsp;·&nbsp; `SEMANTIC_INSERTION_POINT`

        === "Input: SemanticInsertionQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position near the desired insertion location. |
            | `#!kotlin target: SemanticInsertionTarget` | Where to compute the insertion point relative to the position. |
        === "Output: SemanticInsertionResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin insertionOffset: Int` | Zero-based byte offset where new code should be inserted. |
            | `#!kotlin filePath: String` | Absolute path of the file containing the insertion point. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/diagnostics — Run compilation diagnostics for files"

        **Capability** &nbsp;·&nbsp; `DIAGNOSTICS`

        === "Input: DiagnosticsQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` | Absolute paths of the files to analyze for diagnostics. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 500" } | Maximum number of diagnostic records to return. |
            | `#!kotlin pageToken: String?` | Opaque continuation token from the preceding diagnostics page. |
        === "Output: DiagnosticsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin diagnostics: List<Diagnostic>` | List of compilation diagnostics found in the requested files. |
            | `#!kotlin fileStatuses: List<FileAnalysisStatus>` | Typed semantic terminal state for every requested file. |
            | `#!kotlin semanticOutcome: SemanticAnalysisOutcome` | Whether semantic evidence is complete for every requested file. |
            | `#!kotlin requestedFileCount: Int` | Number of files requested for semantic analysis. |
            | `#!kotlin analyzedFileCount: Int` | Number of requested files successfully analyzed. |
            | `#!kotlin skippedFileCount: Int` | Number of requested files not analyzed. |
            | `#!kotlin severityCounts: DiagnosticSeverityCounts` | Exact severity counts across every diagnostic, including records outside this page. |
            | `#!kotlin cardinality: EXACT` | Exact diagnostic cardinality across every page. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/file-outline — Get a hierarchical symbol outline for a file"

        **Capability** &nbsp;·&nbsp; `FILE_OUTLINE`

        === "Input: FileOutlineQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePath: String` | Absolute path of the file to outline. |
        === "Output: FileOutlineResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin symbols: List<OutlineSymbol>` | Top-level symbols in the file, each containing nested children. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/workspace-symbol — Search the workspace for symbols by name pattern"

        **Capability** &nbsp;·&nbsp; `WORKSPACE_SYMBOL_SEARCH`

        === "Input: WorkspaceSymbolQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin pattern: String` | Search pattern to match against symbol names. |
            | `#!kotlin kind: SymbolKind?` | Filter results to symbols of this kind only. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of symbols to return. |
            | `#!kotlin regex: Boolean` :material-information-outline:{ title="Default: false" } | When true, treats the pattern as a regular expression. |
            | `#!kotlin includeDeclarationScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the declarationScope field on each matched symbol. |
        === "Output: WorkspaceSymbolResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin symbols: List<Symbol>` | Symbols matching the search pattern. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/workspace-search — Search workspace file contents for text patterns"

        **Capability** &nbsp;·&nbsp; `WORKSPACE_SEARCH`

        === "Input: WorkspaceSearchQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin pattern: String` | Search pattern to match as a substring or regex. |
            | `#!kotlin regex: Boolean` :material-information-outline:{ title="Default: false" } | When true, treats the pattern as a regular expression. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of matches to return. |
            | `#!kotlin fileGlob: String?` | Optional glob that restricts which files are searched. |
            | `#!kotlin caseSensitive: Boolean` :material-information-outline:{ title="Default: true" } | When true, matches text with case sensitivity. |
        === "Output: WorkspaceSearchResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin matches: List<SearchMatch>` | Matched lines with absolute file path, line, column, and preview text. |
            | `#!kotlin truncated: Boolean` :material-information-outline:{ title="Default: false" } | True when the result stopped at `maxResults`. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/workspace-files — List workspace modules and optional source files"

        **Capability** &nbsp;·&nbsp; `WORKSPACE_FILES`

        === "Input: WorkspaceFilesQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin kindDomain: WorkspaceFileKindDomain` :material-information-outline:{ title="Default: MIXED" } | Closed file-kind domain fingerprinted by workspace inventory paging. |
            | `#!kotlin moduleName: String?` | Filter to a single module by name. Omit to list all modules. |
            | `#!kotlin includeFiles: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes individual file paths for each module. |
            | `#!kotlin maxFilesPerModule: Int?` :material-information-outline:{ title="Default: null" } | Maximum file paths to return per module when includeFiles is true. Omit to use the server maxResults limit. |
            | `#!kotlin snapshotToken: String?` | Opaque workspace inventory snapshot handle returned by a metadata request. |
            | `#!kotlin pageToken: String?` | Opaque single-use module page handle returned by the preceding page. |
        === "Output: WorkspaceFilesResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin modules: List<WorkspaceModule>` | List of workspace modules visible to the daemon. |
            | `#!kotlin snapshotToken: String` | Opaque reusable handle identifying the coherent workspace inventory snapshot. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/workspace-files-continuation — Issue or consume public workspace-file continuation state"

        === "Input: WorkspaceFilesContinuationQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin action: WorkspaceFilesContinuationAction` | Whether this internal request issues a new handle or consumes an existing handle. |
            | `#!kotlin identity: WorkspaceFilesPublicContinuationIdentity` | Exact workspace, backend, normalized query, projection, and limit bound to the handle. |
            | `#!kotlin state: WorkspaceFilesPublicContinuationState?` | Server-owned continuation state supplied only when issuing a handle. |
            | `#!kotlin pageToken: String?` | Opaque single-use public continuation handle supplied only when consuming a handle. |
        === "Output: WorkspaceFilesContinuationResult"

            | Variant |
            |---------|
            | `Issued` |
            | `Consumed` |

    ??? info "raw/implementations — Find concrete implementations and subclasses for a declaration"

        **Capability** &nbsp;·&nbsp; `IMPLEMENTATIONS`

        === "Input: ImplementationsQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the interface or abstract class. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of implementation symbols to return. |
        === "Output: ImplementationsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin declaration: Symbol` | The interface or abstract class symbol that was queried. |
            | `#!kotlin implementations: List<Symbol>` | Concrete implementations or subclasses found. |
            | `#!kotlin exhaustive: Boolean` :material-information-outline:{ title="Default: true" } | True when all implementations were found within maxResults. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/code-actions — Return available code actions at a file position"

        **Capability** &nbsp;·&nbsp; `CODE_ACTIONS`

        === "Input: CodeActionsQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position to query for available code actions. |
            | `#!kotlin diagnosticCode: String?` | Filter to actions that address this diagnostic code. |
        === "Output: CodeActionsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin actions: List<CodeAction>` | Available code actions at the queried position. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/completions — Return completion candidates available at a file position"

        **Capability** &nbsp;·&nbsp; `COMPLETIONS`

        === "Input: CompletionsQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position where completions are requested. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of completion items to return. |
            | `#!kotlin kindFilter: List<SymbolKind>?` | Restrict results to these symbol kinds only. |
        === "Output: CompletionsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin items: List<CompletionItem>` | Completion candidates available at the queried position. |
            | `#!kotlin exhaustive: Boolean` :material-information-outline:{ title="Default: true" } | True when all candidates were returned within maxResults. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

=== "Mutation operations"

    !!! abstract "At a glance"

        4 operations that modify workspace state: rename, optimize imports, apply edits, and refresh.

    ??? info "raw/rename — Plan a symbol rename (dry-run by default)"

        **Capability** &nbsp;·&nbsp; `RENAME`

        === "Input: RenameQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol to rename. |
            | `#!kotlin newName: String` | The new name to assign to the symbol. |
            | `#!kotlin dryRun: Boolean` :material-information-outline:{ title="Default: true" } | When true (default), computes edits without applying them. |
        === "Output: RenameResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin edits: List<TextEdit>` | Text edits needed to perform the rename across the workspace. |
            | `#!kotlin fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
            | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that would be modified. |
            | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the rename search. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/optimize-imports — Optimize imports for one or more files"

        **Capability** &nbsp;·&nbsp; `OPTIMIZE_IMPORTS`

        === "Input: ImportOptimizeQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` | Absolute paths of the files whose imports should be optimized. |
        === "Output: ImportOptimizeResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin edits: List<TextEdit>` | Text edits that remove unused imports and sort the remainder. |
            | `#!kotlin fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
            | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that were modified. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/apply-edits — Apply a prepared edit plan with conflict detection"

        **Capability** &nbsp;·&nbsp; `APPLY_EDITS`

        === "Input: ApplyEditsQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin edits: List<TextEdit>` | Text edits to apply, typically from a prior rename or code action. |
            | `#!kotlin fileHashes: List<FileHash>` | Expected file hashes for conflict detection before writing. |
            | `#!kotlin fileOperations: List<FileOperation>` :material-information-outline:{ title="Default: emptyList()" } | Optional file create or delete operations to perform. |
        === "Output: ApplyEditsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin applied: List<TextEdit>` | Text edits that were successfully applied. |
            | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that were modified. |
            | `#!kotlin createdFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files created by file operations. |
            | `#!kotlin deletedFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files deleted by file operations. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "raw/workspace-refresh — Force a targeted or full workspace state refresh"

        **Capability** &nbsp;·&nbsp; `REFRESH_WORKSPACE`

        === "Input: RefreshQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files to refresh. Empty for a full workspace refresh. |
        === "Output: RefreshResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin refreshedFiles: List<String>` | Absolute paths whose semantic admission completed. |
            | `#!kotlin removedFiles: List<String>` | Absolute paths confirmed removed from the workspace. |
            | `#!kotlin fullRefresh: Boolean` | True when an unbounded full workspace refresh was performed. |
            | `#!kotlin fileStatuses: List<SemanticAdmissionStatus>` | Ordered semantic-admission state for every focused refresh path. |
            | `#!kotlin semanticOutcome: SemanticAnalysisOutcome` | Whether every existing focused path reached semantic admission. |
            | `#!kotlin requestedFileCount: Int` | Number of existing paths that required semantic admission. |
            | `#!kotlin analyzedFileCount: Int` | Number of existing paths that reached semantic admission. |
            | `#!kotlin skippedFileCount: Int` | Number of existing paths that did not reach semantic admission. |
            | `#!kotlin removedFileCount: Int` | Number of focused paths confirmed removed. |
            | `#!kotlin attemptCount: Int` | Number of admission probes performed before returning. |
            | `#!kotlin elapsedMillis: Long` | Elapsed bounded-wait time in milliseconds. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |
