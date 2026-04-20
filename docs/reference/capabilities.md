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

        3 operations for health checks, runtime status, and capability discovery. No capability gating required.

    ??? info "health — Basic health check"

        === "Input"

            _No parameters._
        === "Output: HealthResponse"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin status: String` :material-information-outline:{ title="Default: &quot;ok&quot;" } | Health status string, always "ok" when the daemon is responsive. |
            | `#!kotlin backendName: String` | Identifier of the analysis backend (e.g. "standalone" or "intellij"). |
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

        12 read-only operations for querying symbols, references, hierarchies, diagnostics, outlines, and completions.

    ??? info "symbol/resolve — Resolve the symbol at a file position"

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

    ??? info "references — Find all references to the symbol at a file position"

        **Capability** &nbsp;·&nbsp; `FIND_REFERENCES`

        === "Input: ReferencesQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol whose references to find. |
            | `#!kotlin includeDeclaration: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes the symbol's own declaration in the results. |
        === "Output: ReferencesResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin declaration: Symbol?` | The resolved declaration symbol, included when `includeDeclaration` was set. |
            | `#!kotlin references: List<Location>` | List of source locations where the symbol is referenced. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the search. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "call-hierarchy — Expand a bounded incoming or outgoing call tree"

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

    ??? info "type-hierarchy — Expand supertypes and subtypes from a resolved symbol"

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

    ??? info "semantic-insertion-point — Find the best insertion point for a new declaration"

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

    ??? info "diagnostics — Run compilation diagnostics for files"

        **Capability** &nbsp;·&nbsp; `DIAGNOSTICS`

        === "Input: DiagnosticsQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` | Absolute paths of the files to analyze for diagnostics. |
        === "Output: DiagnosticsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin diagnostics: List<Diagnostic>` | List of compilation diagnostics found in the requested files. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "file-outline — Get a hierarchical symbol outline for a file"

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

    ??? info "workspace-symbol — Search the workspace for symbols by name pattern"

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

    ??? info "workspace/files — List workspace modules and source files"

        **Capability** &nbsp;·&nbsp; `WORKSPACE_FILES`

        === "Input: WorkspaceFilesQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin moduleName: String?` | Filter to a single module by name. Omit to list all modules. |
            | `#!kotlin includeFiles: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes individual file paths for each module. |
        === "Output: WorkspaceFilesResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin modules: List<WorkspaceModule>` | List of workspace modules visible to the daemon. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "implementations — Find concrete implementations and subclasses for a declaration"

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

    ??? info "code-actions — Return available code actions at a file position"

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

    ??? info "completions — Return completion candidates available at a file position"

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

    ??? info "rename — Plan a symbol rename (dry-run by default)"

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

    ??? info "imports/optimize — Optimize imports for one or more files"

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

    ??? info "edits/apply — Apply a prepared edit plan with conflict detection"

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

    ??? info "workspace/refresh — Force a targeted or full workspace state refresh"

        **Capability** &nbsp;·&nbsp; `REFRESH_WORKSPACE`

        === "Input: RefreshQuery"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files to refresh. Empty for a full workspace refresh. |
        === "Output: RefreshResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin refreshedFiles: List<String>` | Absolute paths of files whose state was refreshed. |
            | `#!kotlin removedFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files that were removed from the workspace. |
            | `#!kotlin fullRefresh: Boolean` | True when a full workspace refresh was performed. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |
