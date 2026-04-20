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

    ??? info "Basic health check"


        ??? info "Output: HealthResponse"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin status: String` :material-information-outline:{ title="Default: &quot;ok&quot;" } | Health status string, always "ok" when the daemon is responsive. |
            | `#!kotlin backendName: String` | Identifier of the analysis backend (e.g. "standalone" or "intellij"). |
            | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
            | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Detailed runtime state including indexing progress"


        ??? info "Output: RuntimeStatusResponse"

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

    ??? info "Advertised read and mutation capabilities"


        ??? info "Output: BackendCapabilities"

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

    ??? info "Resolve Symbol"


        ??? info "Input: SymbolQuery" (capability: RESOLVE_SYMBOL)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol to resolve. |
            | `#!kotlin includeDeclarationScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the declarationScope field on the resolved symbol. |
            | `#!kotlin includeDocumentation: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the documentation field on the resolved symbol. |

        ??? info "Output: SymbolResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin symbol: Symbol` | The resolved symbol at the queried position. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Find References"


        ??? info "Input: ReferencesQuery" (capability: FIND_REFERENCES)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol whose references to find. |
            | `#!kotlin includeDeclaration: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes the symbol's own declaration in the results. |

        ??? info "Output: ReferencesResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin declaration: Symbol?` | The resolved declaration symbol, included when `includeDeclaration` was set. |
            | `#!kotlin references: List<Location>` | List of source locations where the symbol is referenced. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the search. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Call Hierarchy"


        ??? info "Input: CallHierarchyQuery" (capability: CALL_HIERARCHY)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the function or method to expand. |
            | `#!kotlin direction: CallDirection` | INCOMING for callers or OUTGOING for callees. |
            | `#!kotlin depth: Int` :material-information-outline:{ title="Default: 3" } | Maximum tree depth to traverse. |
            | `#!kotlin maxTotalCalls: Int` :material-information-outline:{ title="Default: 256" } | Maximum total call nodes to return across the entire tree. |
            | `#!kotlin maxChildrenPerNode: Int` :material-information-outline:{ title="Default: 64" } | Maximum direct children per node before truncation. |
            | `#!kotlin timeoutMillis: Long?` | Optional timeout in milliseconds for the traversal. |

        ??? info "Output: CallHierarchyResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin root: CallNode` | Root node of the call hierarchy tree. |
            | `#!kotlin stats: CallHierarchyStats` | Traversal statistics including truncation indicators. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Type Hierarchy"


        ??? info "Input: TypeHierarchyQuery" (capability: TYPE_HIERARCHY)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the class or interface to expand. |
            | `#!kotlin direction: TypeHierarchyDirection` :material-information-outline:{ title="Default: BOTH" } | SUPERTYPES, SUBTYPES, or BOTH. |
            | `#!kotlin depth: Int` :material-information-outline:{ title="Default: 3" } | Maximum tree depth to traverse. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 256" } | Maximum total nodes to return. |

        ??? info "Output: TypeHierarchyResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin root: TypeHierarchyNode` | Root node of the type hierarchy tree. |
            | `#!kotlin stats: TypeHierarchyStats` | Traversal statistics including truncation indicators. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Semantic Insertion Point"


        ??? info "Input: SemanticInsertionQuery" (capability: SEMANTIC_INSERTION_POINT)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position near the desired insertion location. |
            | `#!kotlin target: SemanticInsertionTarget` | Where to compute the insertion point relative to the position. |

        ??? info "Output: SemanticInsertionResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin insertionOffset: Int` | Zero-based byte offset where new code should be inserted. |
            | `#!kotlin filePath: String` | Absolute path of the file containing the insertion point. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Diagnostics"


        ??? info "Input: DiagnosticsQuery" (capability: DIAGNOSTICS)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` | Absolute paths of the files to analyze for diagnostics. |

        ??? info "Output: DiagnosticsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin diagnostics: List<Diagnostic>` | List of compilation diagnostics found in the requested files. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "File Outline"


        ??? info "Input: FileOutlineQuery" (capability: FILE_OUTLINE)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePath: String` | Absolute path of the file to outline. |

        ??? info "Output: FileOutlineResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin symbols: List<OutlineSymbol>` | Top-level symbols in the file, each containing nested children. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Workspace Symbol Search"


        ??? info "Input: WorkspaceSymbolQuery" (capability: WORKSPACE_SYMBOL_SEARCH)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin pattern: String` | Search pattern to match against symbol names. |
            | `#!kotlin kind: SymbolKind?` | Filter results to symbols of this kind only. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of symbols to return. |
            | `#!kotlin regex: Boolean` :material-information-outline:{ title="Default: false" } | When true, treats the pattern as a regular expression. |
            | `#!kotlin includeDeclarationScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the declarationScope field on each matched symbol. |

        ??? info "Output: WorkspaceSymbolResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin symbols: List<Symbol>` | Symbols matching the search pattern. |
            | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Workspace Files"


        ??? info "Input: WorkspaceFilesQuery" (capability: WORKSPACE_FILES)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin moduleName: String?` | Filter to a single module by name. Omit to list all modules. |
            | `#!kotlin includeFiles: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes individual file paths for each module. |

        ??? info "Output: WorkspaceFilesResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin modules: List<WorkspaceModule>` | List of workspace modules visible to the daemon. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Implementations"


        ??? info "Input: ImplementationsQuery" (capability: IMPLEMENTATIONS)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the interface or abstract class. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of implementation symbols to return. |

        ??? info "Output: ImplementationsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin declaration: Symbol` | The interface or abstract class symbol that was queried. |
            | `#!kotlin implementations: List<Symbol>` | Concrete implementations or subclasses found. |
            | `#!kotlin exhaustive: Boolean` :material-information-outline:{ title="Default: true" } | True when all implementations were found within maxResults. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Code Actions"


        ??? info "Input: CodeActionsQuery" (capability: CODE_ACTIONS)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position to query for available code actions. |
            | `#!kotlin diagnosticCode: String?` | Filter to actions that address this diagnostic code. |

        ??? info "Output: CodeActionsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin actions: List<CodeAction>` | Available code actions at the queried position. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Completions"


        ??? info "Input: CompletionsQuery" (capability: COMPLETIONS)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position where completions are requested. |
            | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of completion items to return. |
            | `#!kotlin kindFilter: List<SymbolKind>?` | Restrict results to these symbol kinds only. |

        ??? info "Output: CompletionsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin items: List<CompletionItem>` | Completion candidates available at the queried position. |
            | `#!kotlin exhaustive: Boolean` :material-information-outline:{ title="Default: true" } | True when all candidates were returned within maxResults. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

=== "Mutation operations"

    !!! abstract "At a glance"

        4 operations that modify workspace state: rename, optimize imports, apply edits, and refresh.

    ??? info "Rename"


        ??? info "Input: RenameQuery" (capability: RENAME)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin position: FilePosition` | File position identifying the symbol to rename. |
            | `#!kotlin newName: String` | The new name to assign to the symbol. |
            | `#!kotlin dryRun: Boolean` :material-information-outline:{ title="Default: true" } | When true (default), computes edits without applying them. |

        ??? info "Output: RenameResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin edits: List<TextEdit>` | Text edits needed to perform the rename across the workspace. |
            | `#!kotlin fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
            | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that would be modified. |
            | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the rename search. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Optimize Imports"


        ??? info "Input: ImportOptimizeQuery" (capability: OPTIMIZE_IMPORTS)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` | Absolute paths of the files whose imports should be optimized. |

        ??? info "Output: ImportOptimizeResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin edits: List<TextEdit>` | Text edits that remove unused imports and sort the remainder. |
            | `#!kotlin fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
            | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that were modified. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Apply Edits"


        ??? info "Input: ApplyEditsQuery" (capability: APPLY_EDITS)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin edits: List<TextEdit>` | Text edits to apply, typically from a prior rename or code action. |
            | `#!kotlin fileHashes: List<FileHash>` | Expected file hashes for conflict detection before writing. |
            | `#!kotlin fileOperations: List<FileOperation>` :material-information-outline:{ title="Default: emptyList()" } | Optional file create or delete operations to perform. |

        ??? info "Output: ApplyEditsResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin applied: List<TextEdit>` | Text edits that were successfully applied. |
            | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that were modified. |
            | `#!kotlin createdFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files created by file operations. |
            | `#!kotlin deletedFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files deleted by file operations. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

    ??? info "Refresh Workspace"


        ??? info "Input: RefreshQuery" (capability: REFRESH_WORKSPACE)

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin filePaths: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files to refresh. Empty for a full workspace refresh. |

        ??? info "Output: RefreshResult"

            | Signature | Description |
            |-----------|-------------|
            | `#!kotlin refreshedFiles: List<String>` | Absolute paths of files whose state was refreshed. |
            | `#!kotlin removedFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files that were removed from the workspace. |
            | `#!kotlin fullRefresh: Boolean` | True when a full workspace refresh was performed. |
            | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |
