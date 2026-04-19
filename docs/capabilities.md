---
title: Capabilities
---

# Capabilities

Every operation the Kast analysis daemon supports, organized by
category. Expand any operation to see its input and output schemas.

## System operations

### health

Basic health check.

**Category:** system | **JSON-RPC method:** `health`

??? info "Output: HealthResponse"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `status` | `String` |  | Health status string, always "ok" when the daemon is responsive. |
    | `backendName` | `String` | ✓ | Identifier of the analysis backend (e.g. "standalone" or "intellij"). |
    | `backendVersion` | `String` | ✓ | Version string of the analysis backend. |
    | `workspaceRoot` | `String` | ✓ | Absolute path of the workspace root directory. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### runtime/status

Detailed runtime state including indexing progress.

**Category:** system | **JSON-RPC method:** `runtime/status`

??? info "Output: RuntimeStatusResponse"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `state` | `RuntimeState` | ✓ | Current runtime state: STARTING, INDEXING, READY, or DEGRADED. |
    | `healthy` | `Boolean` | ✓ | True when the daemon is responsive and not in an error state. |
    | `active` | `Boolean` | ✓ | True when the daemon has an active workspace session. |
    | `indexing` | `Boolean` | ✓ | True when the daemon is currently indexing the workspace. |
    | `backendName` | `String` | ✓ | Identifier of the analysis backend. |
    | `backendVersion` | `String` | ✓ | Version string of the analysis backend. |
    | `workspaceRoot` | `String` | ✓ | Absolute path of the workspace root directory. |
    | `message` | `String?` |  | Human-readable status message with additional context. |
    | `warnings` | `List<String>` |  | Active warning messages about the runtime environment. |
    | `sourceModuleNames` | `List<String>` |  | Names of source modules discovered in the workspace. |
    | `dependentModuleNamesBySourceModuleName` | `Map<String, List<String>>` |  | Map from source module name to its dependency module names. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### capabilities

Advertised read and mutation capabilities.

**Category:** system | **JSON-RPC method:** `capabilities`

??? info "Output: BackendCapabilities"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `backendName` | `String` | ✓ | Identifier of the analysis backend. |
    | `backendVersion` | `String` | ✓ | Version string of the analysis backend. |
    | `workspaceRoot` | `String` | ✓ | Absolute path of the workspace root directory. |
    | `readCapabilities` | `List<ReadCapability>` | ✓ | Set of read operations this backend supports. |
    | `mutationCapabilities` | `List<MutationCapability>` | ✓ | Set of mutation operations this backend supports. |
    | `limits` | `ServerLimits` | ✓ | Server-enforced resource limits. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

## Read operations

### symbol/resolve

Resolve the symbol at a file position.

**Capability:** `RESOLVE_SYMBOL` | **Category:** read | **JSON-RPC method:** `symbol/resolve`

??? info "Input: SymbolQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position identifying the symbol to resolve. |
    | `includeDeclarationScope` | `Boolean` |  | When true, populates the declarationScope field on the resolved symbol. |
    | `includeDocumentation` | `Boolean` |  | When true, populates the documentation field on the resolved symbol. |

??? info "Output: SymbolResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `symbol` | `Symbol` | ✓ | The resolved symbol at the queried position. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### references

Find all references to the symbol at a file position.

**Capability:** `FIND_REFERENCES` | **Category:** read | **JSON-RPC method:** `references`

??? info "Input: ReferencesQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position identifying the symbol whose references to find. |
    | `includeDeclaration` | `Boolean` |  | When true, includes the symbol's own declaration in the results. |

??? info "Output: ReferencesResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `declaration` | `Symbol?` |  | The resolved declaration symbol, included when `includeDeclaration` was set. |
    | `references` | `List<Location>` | ✓ | List of source locations where the symbol is referenced. |
    | `page` | `PageInfo?` |  | Pagination metadata when results are truncated. |
    | `searchScope` | `SearchScope?` |  | Describes the scope and exhaustiveness of the search. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### call-hierarchy

Expand a bounded incoming or outgoing call tree.

**Capability:** `CALL_HIERARCHY` | **Category:** read | **JSON-RPC method:** `call-hierarchy`

??? info "Input: CallHierarchyQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position identifying the function or method to expand. |
    | `direction` | `CallDirection` | ✓ | INCOMING for callers or OUTGOING for callees. |
    | `depth` | `Int` |  | Maximum tree depth to traverse. |
    | `maxTotalCalls` | `Int` |  | Maximum total call nodes to return across the entire tree. |
    | `maxChildrenPerNode` | `Int` |  | Maximum direct children per node before truncation. |
    | `timeoutMillis` | `Long?` |  | Optional timeout in milliseconds for the traversal. |

??? info "Output: CallHierarchyResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `root` | `CallNode` | ✓ | Root node of the call hierarchy tree. |
    | `stats` | `CallHierarchyStats` | ✓ | Traversal statistics including truncation indicators. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### type-hierarchy

Expand supertypes and subtypes from a resolved symbol.

**Capability:** `TYPE_HIERARCHY` | **Category:** read | **JSON-RPC method:** `type-hierarchy`

??? info "Input: TypeHierarchyQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position identifying the class or interface to expand. |
    | `direction` | `TypeHierarchyDirection` |  | SUPERTYPES, SUBTYPES, or BOTH. |
    | `depth` | `Int` |  | Maximum tree depth to traverse. |
    | `maxResults` | `Int` |  | Maximum total nodes to return. |

??? info "Output: TypeHierarchyResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `root` | `TypeHierarchyNode` | ✓ | Root node of the type hierarchy tree. |
    | `stats` | `TypeHierarchyStats` | ✓ | Traversal statistics including truncation indicators. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### semantic-insertion-point

Find the best insertion point for a new declaration.

**Capability:** `SEMANTIC_INSERTION_POINT` | **Category:** read | **JSON-RPC method:** `semantic-insertion-point`

??? info "Input: SemanticInsertionQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position near the desired insertion location. |
    | `target` | `SemanticInsertionTarget` | ✓ | Where to compute the insertion point relative to the position. |

??? info "Output: SemanticInsertionResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `insertionOffset` | `Int` | ✓ | Zero-based byte offset where new code should be inserted. |
    | `filePath` | `String` | ✓ | Absolute path of the file containing the insertion point. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### diagnostics

Run compilation diagnostics for files.

**Capability:** `DIAGNOSTICS` | **Category:** read | **JSON-RPC method:** `diagnostics`

??? info "Input: DiagnosticsQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `filePaths` | `List<String>` | ✓ | Absolute paths of the files to analyze for diagnostics. |

??? info "Output: DiagnosticsResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `diagnostics` | `List<Diagnostic>` | ✓ | List of compilation diagnostics found in the requested files. |
    | `page` | `PageInfo?` |  | Pagination metadata when results are truncated. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### file-outline

Get a hierarchical symbol outline for a file.

**Capability:** `FILE_OUTLINE` | **Category:** read | **JSON-RPC method:** `file-outline`

??? info "Input: FileOutlineQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `filePath` | `String` | ✓ | Absolute path of the file to outline. |

??? info "Output: FileOutlineResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `symbols` | `List<OutlineSymbol>` | ✓ | Top-level symbols in the file, each containing nested children. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### workspace-symbol

Search the workspace for symbols by name pattern.

**Capability:** `WORKSPACE_SYMBOL_SEARCH` | **Category:** read | **JSON-RPC method:** `workspace-symbol`

??? info "Input: WorkspaceSymbolQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `pattern` | `String` | ✓ | Search pattern to match against symbol names. |
    | `kind` | `SymbolKind?` |  | Filter results to symbols of this kind only. |
    | `maxResults` | `Int` |  | Maximum number of symbols to return. |
    | `regex` | `Boolean` |  | When true, treats the pattern as a regular expression. |
    | `includeDeclarationScope` | `Boolean` |  | When true, populates the declarationScope field on each matched symbol. |

??? info "Output: WorkspaceSymbolResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `symbols` | `List<Symbol>` | ✓ | Symbols matching the search pattern. |
    | `page` | `PageInfo?` |  | Pagination metadata when results are truncated. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### workspace/files

List workspace modules and source files.

**Capability:** `WORKSPACE_FILES` | **Category:** read | **JSON-RPC method:** `workspace/files`

??? info "Input: WorkspaceFilesQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `moduleName` | `String?` |  | Filter to a single module by name. Omit to list all modules. |
    | `includeFiles` | `Boolean` |  | When true, includes individual file paths for each module. |

??? info "Output: WorkspaceFilesResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `modules` | `List<WorkspaceModule>` | ✓ | List of workspace modules visible to the daemon. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### implementations

Find concrete implementations and subclasses for a declaration.

**Capability:** `IMPLEMENTATIONS` | **Category:** read | **JSON-RPC method:** `implementations`

??? info "Input: ImplementationsQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position identifying the interface or abstract class. |
    | `maxResults` | `Int` |  | Maximum number of implementation symbols to return. |

??? info "Output: ImplementationsResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `declaration` | `Symbol` | ✓ | The interface or abstract class symbol that was queried. |
    | `implementations` | `List<Symbol>` | ✓ | Concrete implementations or subclasses found. |
    | `exhaustive` | `Boolean` |  | True when all implementations were found within maxResults. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### code-actions

Return available code actions at a file position.

**Capability:** `CODE_ACTIONS` | **Category:** read | **JSON-RPC method:** `code-actions`

??? info "Input: CodeActionsQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position to query for available code actions. |
    | `diagnosticCode` | `String?` |  | Filter to actions that address this diagnostic code. |

??? info "Output: CodeActionsResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `actions` | `List<CodeAction>` | ✓ | Available code actions at the queried position. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### completions

Return completion candidates available at a file position.

**Capability:** `COMPLETIONS` | **Category:** read | **JSON-RPC method:** `completions`

??? info "Input: CompletionsQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position where completions are requested. |
    | `maxResults` | `Int` |  | Maximum number of completion items to return. |
    | `kindFilter` | `List<SymbolKind>?` |  | Restrict results to these symbol kinds only. |

??? info "Output: CompletionsResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `items` | `List<CompletionItem>` | ✓ | Completion candidates available at the queried position. |
    | `exhaustive` | `Boolean` |  | True when all candidates were returned within maxResults. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

## Mutation operations

### rename

Plan a symbol rename (dry-run by default).

**Capability:** `RENAME` | **Category:** mutation | **JSON-RPC method:** `rename`

??? info "Input: RenameQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `position` | `FilePosition` | ✓ | File position identifying the symbol to rename. |
    | `newName` | `String` | ✓ | The new name to assign to the symbol. |
    | `dryRun` | `Boolean` |  | When true (default), computes edits without applying them. |

??? info "Output: RenameResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `edits` | `List<TextEdit>` | ✓ | Text edits needed to perform the rename across the workspace. |
    | `fileHashes` | `List<FileHash>` | ✓ | File hashes at edit-plan time for conflict detection. |
    | `affectedFiles` | `List<String>` | ✓ | Absolute paths of all files that would be modified. |
    | `searchScope` | `SearchScope?` |  | Describes the scope and exhaustiveness of the rename search. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### imports/optimize

Optimize imports for one or more files.

**Capability:** `OPTIMIZE_IMPORTS` | **Category:** mutation | **JSON-RPC method:** `imports/optimize`

??? info "Input: ImportOptimizeQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `filePaths` | `List<String>` | ✓ | Absolute paths of the files whose imports should be optimized. |

??? info "Output: ImportOptimizeResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `edits` | `List<TextEdit>` | ✓ | Text edits that remove unused imports and sort the remainder. |
    | `fileHashes` | `List<FileHash>` | ✓ | File hashes at edit-plan time for conflict detection. |
    | `affectedFiles` | `List<String>` | ✓ | Absolute paths of all files that were modified. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### edits/apply

Apply a prepared edit plan with conflict detection.

**Capability:** `APPLY_EDITS` | **Category:** mutation | **JSON-RPC method:** `edits/apply`

??? info "Input: ApplyEditsQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `edits` | `List<TextEdit>` | ✓ | Text edits to apply, typically from a prior rename or code action. |
    | `fileHashes` | `List<FileHash>` | ✓ | Expected file hashes for conflict detection before writing. |
    | `fileOperations` | `List<FileOperation>` |  | Optional file create or delete operations to perform. |

??? info "Output: ApplyEditsResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `applied` | `List<TextEdit>` | ✓ | Text edits that were successfully applied. |
    | `affectedFiles` | `List<String>` | ✓ | Absolute paths of all files that were modified. |
    | `createdFiles` | `List<String>` |  | Absolute paths of files created by file operations. |
    | `deletedFiles` | `List<String>` |  | Absolute paths of files deleted by file operations. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |

### workspace/refresh

Force a targeted or full workspace state refresh.

**Capability:** `REFRESH_WORKSPACE` | **Category:** mutation | **JSON-RPC method:** `workspace/refresh`

??? info "Input: RefreshQuery"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `filePaths` | `List<String>` |  | Absolute paths of files to refresh. Empty for a full workspace refresh. |

??? info "Output: RefreshResult"

    | Field | Type | Required | Description |
    |-------|------|----------|-------------|
    | `refreshedFiles` | `List<String>` | ✓ | Absolute paths of files whose state was refreshed. |
    | `removedFiles` | `List<String>` |  | Absolute paths of files that were removed from the workspace. |
    | `fullRefresh` | `Boolean` | ✓ | True when a full workspace refresh was performed. |
    | `schemaVersion` | `Int` |  | Protocol schema version for forward compatibility. |
