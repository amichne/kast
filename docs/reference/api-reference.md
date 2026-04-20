---
title: API reference
hide:
    - toc
---

# API reference

Complete reference for every JSON-RPC method in the Kast analysis
daemon, including input/output schemas, examples, and behavioral notes.

=== "System operations"

    !!! abstract "At a glance"

        3 operations for health checks, runtime status, and capability discovery. No capability gating required.

    ??? example "Basic health check"

        Returns a lightweight health check confirming the daemon is responsive. Use this before dispatching heavier queries.

        <div style="text-align:right">
        <code>health</code>
        </div>

        #### Output: HealthResponse

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin status: String` :material-information-outline:{ title="Default: &quot;ok&quot;" } | Health status string, always "ok" when the daemon is responsive. |
        | `#!kotlin backendName: String` | Identifier of the analysis backend (e.g. "standalone" or "intellij"). |
        | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
        | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast health --workspace-root=/path/to/project
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "health",
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "status": "ok",
                    "backendName": "fake",
                    "backendVersion": "0.1.0-test",
                    "workspaceRoot": "/workspace",
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```


    ??? example "Detailed runtime state including indexing progress"

        Returns the full runtime state including indexing progress, backend identity, and workspace root. Use this to verify readiness before running analysis commands.

        <div style="text-align:right">
        <code>runtime/status</code>
        </div>

        #### Output: RuntimeStatusResponse

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

        === "CLI example"

            ```bash
            kast workspace status --workspace-root=/path/to/project
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "runtime/status",
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "fake",
                    "backendVersion": "0.1.0-test",
                    "workspaceRoot": "/workspace",
                    "warnings": [],
                    "sourceModuleNames": [],
                    "dependentModuleNamesBySourceModuleName": {},
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```


    ??? example "Advertised read and mutation capabilities"

        Lists every read and mutation capability the current backend advertises, along with server limits. Query this before calling an operation to confirm it is available.

        <div style="text-align:right">
        <code>capabilities</code>
        </div>

        #### Output: BackendCapabilities

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin backendName: String` | Identifier of the analysis backend. |
        | `#!kotlin backendVersion: String` | Version string of the analysis backend. |
        | `#!kotlin workspaceRoot: String` | Absolute path of the workspace root directory. |
        | `#!kotlin readCapabilities: List<ReadCapability>` | Set of read operations this backend supports. |
        | `#!kotlin mutationCapabilities: List<MutationCapability>` | Set of mutation operations this backend supports. |
        | `#!kotlin limits: ServerLimits` | Server-enforced resource limits. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast capabilities --workspace-root=/path/to/project
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "capabilities",
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "backendName": "fake",
                    "backendVersion": "0.1.0-test",
                    "workspaceRoot": "/workspace",
                    "readCapabilities": [
                        "RESOLVE_SYMBOL",
                        "FIND_REFERENCES",
                        "CALL_HIERARCHY",
                        "TYPE_HIERARCHY",
                        "SEMANTIC_INSERTION_POINT",
                        "DIAGNOSTICS",
                        "FILE_OUTLINE",
                        "WORKSPACE_SYMBOL_SEARCH",
                        "WORKSPACE_FILES",
                        "IMPLEMENTATIONS",
                        "CODE_ACTIONS",
                        "COMPLETIONS"
                    ],
                    "mutationCapabilities": [
                        "RENAME",
                        "APPLY_EDITS",
                        "FILE_OPERATIONS",
                        "OPTIMIZE_IMPORTS",
                        "REFRESH_WORKSPACE"
                    ],
                    "limits": {
                        "maxResults": 100,
                        "requestTimeoutMillis": 30000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```


=== "Read operations"

    !!! abstract "At a glance"

        12 read-only operations for querying symbols, references, hierarchies, diagnostics, outlines, and completions.

    ??? example "Resolve Symbol"

        Resolves the symbol at a file position, returning its fully qualified name, kind, location, and optional metadata such as type information and documentation.

        <div style="text-align:right">
        <code>RESOLVE_SYMBOL</code>&ensp;<code>symbol/resolve</code>
        </div>

        #### Input: SymbolQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position identifying the symbol to resolve. |
        | `#!kotlin includeDeclarationScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the declarationScope field on the resolved symbol. |
        | `#!kotlin includeDocumentation: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the documentation field on the resolved symbol. |

        #### Output: SymbolResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin symbol: Symbol` | The resolved symbol at the queried position. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast resolve --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "symbol/resolve",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 20
                    },
                    "includeDeclarationScope": false,
                    "includeDocumentation": false
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "symbol": {
                        "fqName": "sample.greet",
                        "kind": "FUNCTION",
                        "location": {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 20,
                            "endOffset": 25,
                            "startLine": 4,
                            "startColumn": 13,
                            "preview": "greet"
                        },
                        "returnType": "String",
                        "parameters": [
                            {
                                "name": "name",
                                "type": "String",
                                "isVararg": false
                            }
                        ],
                        "documentation": "/** Greets the provided name. */",
                        "containingDeclaration": "sample"
                    },
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - The position must be an absolute file path with a zero-based byte offset.
            - If the offset does not land on a symbol, the daemon returns a NOT_FOUND error.
            - Optional fields like `declarationScope` and `documentation` are only populated when the corresponding query flags are set.

        **Error codes:** `NOT_FOUND`

    ??? example "Find References"

        Finds all references to the symbol at a file position across the workspace. Optionally includes the declaration itself.

        <div style="text-align:right">
        <code>FIND_REFERENCES</code>&ensp;<code>references</code>
        </div>

        #### Input: ReferencesQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position identifying the symbol whose references to find. |
        | `#!kotlin includeDeclaration: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes the symbol's own declaration in the results. |

        #### Output: ReferencesResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin declaration: Symbol?` | The resolved declaration symbol, included when `includeDeclaration` was set. |
        | `#!kotlin references: List<Location>` | List of source locations where the symbol is referenced. |
        | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
        | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the search. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast references --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "references",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 48
                    },
                    "includeDeclaration": true
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "declaration": {
                        "fqName": "sample.greet",
                        "kind": "FUNCTION",
                        "location": {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 20,
                            "endOffset": 25,
                            "startLine": 4,
                            "startColumn": 13,
                            "preview": "greet"
                        },
                        "returnType": "String",
                        "parameters": [
                            {
                                "name": "name",
                                "type": "String",
                                "isVararg": false
                            }
                        ],
                        "documentation": "/** Greets the provided name. */",
                        "containingDeclaration": "sample"
                    },
                    "references": [
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 48,
                            "endOffset": 53,
                            "startLine": 4,
                            "startColumn": 13,
                            "preview": "greet"
                        }
                    ],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Results are workspace-scoped — references outside the current workspace are not returned.
            - Set `includeDeclaration` to true to include the symbol's declaration in the result alongside usage sites.
            - Large result sets are paginated; check the `page` field for continuation.

        **Error codes:** `NOT_FOUND`

    ??? example "Call Hierarchy"

        Expands a bounded incoming or outgoing call tree from a function or method. Use incoming to find callers, outgoing to find callees.

        <div style="text-align:right">
        <code>CALL_HIERARCHY</code>&ensp;<code>call-hierarchy</code>
        </div>

        #### Input: CallHierarchyQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position identifying the function or method to expand. |
        | `#!kotlin direction: CallDirection` | INCOMING for callers or OUTGOING for callees. |
        | `#!kotlin depth: Int` :material-information-outline:{ title="Default: 3" } | Maximum tree depth to traverse. |
        | `#!kotlin maxTotalCalls: Int` :material-information-outline:{ title="Default: 256" } | Maximum total call nodes to return across the entire tree. |
        | `#!kotlin maxChildrenPerNode: Int` :material-information-outline:{ title="Default: 64" } | Maximum direct children per node before truncation. |
        | `#!kotlin timeoutMillis: Long?` | Optional timeout in milliseconds for the traversal. |

        #### Output: CallHierarchyResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin root: CallNode` | Root node of the call hierarchy tree. |
        | `#!kotlin stats: CallHierarchyStats` | Traversal statistics including truncation indicators. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast call-hierarchy --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42 --direction=INCOMING --depth=2
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "call-hierarchy",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 48
                    },
                    "direction": "INCOMING",
                    "depth": 1,
                    "maxTotalCalls": 16,
                    "maxChildrenPerNode": 16
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "root": {
                        "symbol": {
                            "fqName": "sample.greet",
                            "kind": "FUNCTION",
                            "location": {
                                "filePath": "/workspace/src/Sample.kt",
                                "startOffset": 20,
                                "endOffset": 25,
                                "startLine": 4,
                                "startColumn": 13,
                                "preview": "greet"
                            },
                            "returnType": "String",
                            "parameters": [
                                {
                                    "name": "name",
                                    "type": "String",
                                    "isVararg": false
                                }
                            ],
                            "documentation": "/** Greets the provided name. */",
                            "containingDeclaration": "sample"
                        },
                        "children": [
                            {
                                "symbol": {
                                    "fqName": "sample.caller0",
                                    "kind": "FUNCTION",
                                    "location": {
                                        "filePath": "/workspace/src/Sample.kt",
                                        "startOffset": 48,
                                        "endOffset": 53,
                                        "startLine": 4,
                                        "startColumn": 13,
                                        "preview": "greet"
                                    }
                                },
                                "callSite": {
                                    "filePath": "/workspace/src/Sample.kt",
                                    "startOffset": 48,
                                    "endOffset": 53,
                                    "startLine": 4,
                                    "startColumn": 13,
                                    "preview": "greet"
                                },
                                "children": []
                            }
                        ]
                    },
                    "stats": {
                        "totalNodes": 2,
                        "totalEdges": 1,
                        "truncatedNodes": 0,
                        "maxDepthReached": 1,
                        "timeoutReached": false,
                        "maxTotalCallsReached": false,
                        "maxChildrenPerNodeReached": false,
                        "filesVisited": 1
                    },
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Traversal is bounded by `depth`, `maxTotalCalls`, and `maxChildrenPerNode`. The stats object reports whether any bound was reached.
            - Set `direction` to `INCOMING` for callers or `OUTGOING` for callees.
            - Cycles are detected and reported via truncation metadata on the affected node.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Type Hierarchy"

        Expands supertypes and subtypes from a resolved symbol. Use this to understand inheritance relationships.

        <div style="text-align:right">
        <code>TYPE_HIERARCHY</code>&ensp;<code>type-hierarchy</code>
        </div>

        #### Input: TypeHierarchyQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position identifying the class or interface to expand. |
        | `#!kotlin direction: TypeHierarchyDirection` :material-information-outline:{ title="Default: BOTH" } | SUPERTYPES, SUBTYPES, or BOTH. |
        | `#!kotlin depth: Int` :material-information-outline:{ title="Default: 3" } | Maximum tree depth to traverse. |
        | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 256" } | Maximum total nodes to return. |

        #### Output: TypeHierarchyResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin root: TypeHierarchyNode` | Root node of the type hierarchy tree. |
        | `#!kotlin stats: TypeHierarchyStats` | Traversal statistics including truncation indicators. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast type-hierarchy --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42 --direction=BOTH
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "type-hierarchy",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Types.kt",
                        "offset": 45
                    },
                    "direction": "BOTH",
                    "depth": 1,
                    "maxResults": 16
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "root": {
                        "symbol": {
                            "fqName": "sample.FriendlyGreeter",
                            "kind": "CLASS",
                            "location": {
                                "filePath": "/workspace/src/Types.kt",
                                "startOffset": 45,
                                "endOffset": 60,
                                "startLine": 4,
                                "startColumn": 12,
                                "preview": "open class FriendlyGreeter : Greeter"
                            },
                            "containingDeclaration": "sample",
                            "supertypes": [
                                "sample.Greeter"
                            ]
                        },
                        "children": [
                            {
                                "symbol": {
                                    "fqName": "sample.Greeter",
                                    "kind": "INTERFACE",
                                    "location": {
                                        "filePath": "/workspace/src/Types.kt",
                                        "startOffset": 26,
                                        "endOffset": 33,
                                        "startLine": 3,
                                        "startColumn": 11,
                                        "preview": "interface Greeter"
                                    },
                                    "containingDeclaration": "sample"
                                },
                                "children": []
                            },
                            {
                                "symbol": {
                                    "fqName": "sample.LoudGreeter",
                                    "kind": "CLASS",
                                    "location": {
                                        "filePath": "/workspace/src/Types.kt",
                                        "startOffset": 77,
                                        "endOffset": 88,
                                        "startLine": 5,
                                        "startColumn": 7,
                                        "preview": "class LoudGreeter : FriendlyGreeter()"
                                    },
                                    "containingDeclaration": "sample",
                                    "supertypes": [
                                        "sample.FriendlyGreeter"
                                    ]
                                },
                                "children": []
                            }
                        ]
                    },
                    "stats": {
                        "totalNodes": 3,
                        "maxDepthReached": 1,
                        "truncated": false
                    },
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Set `direction` to `SUPERTYPES`, `SUBTYPES`, or `BOTH`.
            - Traversal is bounded by `depth` and `maxResults`. The stats object reports whether truncation occurred.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Semantic Insertion Point"

        Finds the best insertion point for a new declaration relative to a file position. Use this to place generated code at a semantically appropriate location.

        <div style="text-align:right">
        <code>SEMANTIC_INSERTION_POINT</code>&ensp;<code>semantic-insertion-point</code>
        </div>

        #### Input: SemanticInsertionQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position near the desired insertion location. |
        | `#!kotlin target: SemanticInsertionTarget` | Where to compute the insertion point relative to the position. |

        #### Output: SemanticInsertionResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin insertionOffset: Int` | Zero-based byte offset where new code should be inserted. |
        | `#!kotlin filePath: String` | Absolute path of the file containing the insertion point. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast insertion-point --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42 --target=AFTER_IMPORTS
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "semantic-insertion-point",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 0
                    },
                    "target": "FILE_BOTTOM"
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "insertionOffset": 56,
                    "filePath": "/workspace/src/Sample.kt",
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - The `target` field controls where the insertion point is computed: class body start/end, file top/bottom, or after imports.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Diagnostics"

        Runs compilation diagnostics for one or more files, returning errors, warnings, and informational messages with precise source locations.

        <div style="text-align:right">
        <code>DIAGNOSTICS</code>&ensp;<code>diagnostics</code>
        </div>

        #### Input: DiagnosticsQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin filePaths: List<String>` | Absolute paths of the files to analyze for diagnostics. |

        #### Output: DiagnosticsResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin diagnostics: List<Diagnostic>` | List of compilation diagnostics found in the requested files. |
        | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast diagnostics --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "diagnostics",
                "params": {
                    "filePaths": [
                        "/workspace/src/Sample.kt"
                    ]
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "diagnostics": [],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Pass one or more absolute file paths. The daemon analyzes each file and returns all diagnostics sorted by location.
            - Diagnostics reflect the current daemon state. Call `workspace/refresh` first if files were modified outside the daemon.

        **Error codes:** `NOT_FOUND`

    ??? example "File Outline"

        Returns a hierarchical symbol outline for a single file, listing all named declarations and their nesting.

        <div style="text-align:right">
        <code>FILE_OUTLINE</code>&ensp;<code>file-outline</code>
        </div>

        #### Input: FileOutlineQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin filePath: String` | Absolute path of the file to outline. |

        #### Output: FileOutlineResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin symbols: List<OutlineSymbol>` | Top-level symbols in the file, each containing nested children. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast outline --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "file-outline",
                "params": {
                    "filePath": "/workspace/src/Sample.kt"
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "symbols": [
                        {
                            "symbol": {
                                "fqName": "sample.greet",
                                "kind": "FUNCTION",
                                "location": {
                                    "filePath": "/workspace/src/Sample.kt",
                                    "startOffset": 20,
                                    "endOffset": 25,
                                    "startLine": 4,
                                    "startColumn": 13,
                                    "preview": "greet"
                                },
                                "returnType": "String",
                                "parameters": [
                                    {
                                        "name": "name",
                                        "type": "String",
                                        "isVararg": false
                                    }
                                ],
                                "documentation": "/** Greets the provided name. */",
                                "containingDeclaration": "sample"
                            },
                            "children": []
                        }
                    ],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - The outline includes classes, functions, properties, and other named declarations with their fully qualified names.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Workspace Symbol Search"

        Searches the entire workspace for symbols matching a name pattern. Supports substring matching and optional regex.

        <div style="text-align:right">
        <code>WORKSPACE_SYMBOL_SEARCH</code>&ensp;<code>workspace-symbol</code>
        </div>

        #### Input: WorkspaceSymbolQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin pattern: String` | Search pattern to match against symbol names. |
        | `#!kotlin kind: SymbolKind?` | Filter results to symbols of this kind only. |
        | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of symbols to return. |
        | `#!kotlin regex: Boolean` :material-information-outline:{ title="Default: false" } | When true, treats the pattern as a regular expression. |
        | `#!kotlin includeDeclarationScope: Boolean` :material-information-outline:{ title="Default: false" } | When true, populates the declarationScope field on each matched symbol. |

        #### Output: WorkspaceSymbolResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin symbols: List<Symbol>` | Symbols matching the search pattern. |
        | `#!kotlin page: PageInfo?` | Pagination metadata when results are truncated. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast workspace-symbol --workspace-root=/path/to/project --pattern=UserService
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "workspace-symbol",
                "params": {
                    "pattern": "greet",
                    "maxResults": 100,
                    "regex": false,
                    "includeDeclarationScope": false
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "symbols": [
                        {
                            "fqName": "sample.greet",
                            "kind": "FUNCTION",
                            "location": {
                                "filePath": "/workspace/src/Sample.kt",
                                "startOffset": 20,
                                "endOffset": 25,
                                "startLine": 4,
                                "startColumn": 13,
                                "preview": "greet"
                            },
                            "returnType": "String",
                            "parameters": [
                                {
                                    "name": "name",
                                    "type": "String",
                                    "isVararg": false
                                }
                            ],
                            "documentation": "/** Greets the provided name. */",
                            "containingDeclaration": "sample"
                        },
                        {
                            "fqName": "sample.FriendlyGreeter",
                            "kind": "CLASS",
                            "location": {
                                "filePath": "/workspace/src/Types.kt",
                                "startOffset": 45,
                                "endOffset": 60,
                                "startLine": 4,
                                "startColumn": 12,
                                "preview": "open class FriendlyGreeter : Greeter"
                            },
                            "containingDeclaration": "sample",
                            "supertypes": [
                                "sample.Greeter"
                            ]
                        },
                        {
                            "fqName": "sample.Greeter",
                            "kind": "INTERFACE",
                            "location": {
                                "filePath": "/workspace/src/Types.kt",
                                "startOffset": 26,
                                "endOffset": 33,
                                "startLine": 3,
                                "startColumn": 11,
                                "preview": "interface Greeter"
                            },
                            "containingDeclaration": "sample"
                        },
                        {
                            "fqName": "sample.LoudGreeter",
                            "kind": "CLASS",
                            "location": {
                                "filePath": "/workspace/src/Types.kt",
                                "startOffset": 77,
                                "endOffset": 88,
                                "startLine": 5,
                                "startColumn": 7,
                                "preview": "class LoudGreeter : FriendlyGreeter()"
                            },
                            "containingDeclaration": "sample",
                            "supertypes": [
                                "sample.FriendlyGreeter"
                            ]
                        }
                    ],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - The search is case-insensitive by default for substring matching.
            - Set `regex` to true for regular expression patterns.
            - Results are bounded by `maxResults`. Set `kind` to filter by symbol type.

        **Error codes:** `CAPABILITY_NOT_SUPPORTED`

    ??? example "Workspace Files"

        Lists workspace modules and their source files. Use this to discover the project structure visible to the daemon.

        <div style="text-align:right">
        <code>WORKSPACE_FILES</code>&ensp;<code>workspace/files</code>
        </div>

        #### Input: WorkspaceFilesQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin moduleName: String?` | Filter to a single module by name. Omit to list all modules. |
        | `#!kotlin includeFiles: Boolean` :material-information-outline:{ title="Default: false" } | When true, includes individual file paths for each module. |

        #### Output: WorkspaceFilesResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin modules: List<WorkspaceModule>` | List of workspace modules visible to the daemon. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast workspace-files --workspace-root=/path/to/project
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "workspace/files",
                "params": {
                    "includeFiles": false
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "modules": [
                        {
                            "name": "fake-module",
                            "sourceRoots": [
                                "/workspace/src"
                            ],
                            "dependencyModuleNames": [],
                            "files": [],
                            "fileCount": 2
                        }
                    ],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Set `includeFiles` to true to include individual file paths per module.
            - Filter by `moduleName` to inspect a single module.

        **Error codes:** `CAPABILITY_NOT_SUPPORTED`

    ??? example "Implementations"

        Finds concrete implementations and subclasses for an interface or abstract class declaration.

        <div style="text-align:right">
        <code>IMPLEMENTATIONS</code>&ensp;<code>implementations</code>
        </div>

        #### Input: ImplementationsQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position identifying the interface or abstract class. |
        | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of implementation symbols to return. |

        #### Output: ImplementationsResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin declaration: Symbol` | The interface or abstract class symbol that was queried. |
        | `#!kotlin implementations: List<Symbol>` | Concrete implementations or subclasses found. |
        | `#!kotlin exhaustive: Boolean` :material-information-outline:{ title="Default: true" } | True when all implementations were found within maxResults. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast implementations --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "implementations",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Types.kt",
                        "offset": 45
                    },
                    "maxResults": 10
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "declaration": {
                        "fqName": "sample.Greeter",
                        "kind": "INTERFACE",
                        "location": {
                            "filePath": "/workspace/src/Types.kt",
                            "startOffset": 26,
                            "endOffset": 33,
                            "startLine": 3,
                            "startColumn": 11,
                            "preview": "interface Greeter"
                        },
                        "containingDeclaration": "sample"
                    },
                    "implementations": [
                        {
                            "fqName": "sample.LoudGreeter",
                            "kind": "CLASS",
                            "location": {
                                "filePath": "/workspace/src/Types.kt",
                                "startOffset": 77,
                                "endOffset": 88,
                                "startLine": 5,
                                "startColumn": 7,
                                "preview": "class LoudGreeter : FriendlyGreeter()"
                            },
                            "containingDeclaration": "sample",
                            "supertypes": [
                                "sample.FriendlyGreeter"
                            ]
                        }
                    ],
                    "exhaustive": true,
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - The position must point to an interface, abstract class, or open class.
            - Results include the `exhaustive` flag indicating whether all implementations were found within `maxResults`.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Code Actions"

        Returns available code actions at a file position, such as quick fixes and refactoring suggestions.

        <div style="text-align:right">
        <code>CODE_ACTIONS</code>&ensp;<code>code-actions</code>
        </div>

        #### Input: CodeActionsQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position to query for available code actions. |
        | `#!kotlin diagnosticCode: String?` | Filter to actions that address this diagnostic code. |

        #### Output: CodeActionsResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin actions: List<CodeAction>` | Available code actions at the queried position. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast code-actions --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "code-actions",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 0
                    }
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "actions": [],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Code actions are context-dependent and may return an empty list when no actions are applicable.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Completions"

        Returns completion candidates available at a file position. Use this to discover what symbols, keywords, or snippets the compiler suggests.

        <div style="text-align:right">
        <code>COMPLETIONS</code>&ensp;<code>completions</code>
        </div>

        #### Input: CompletionsQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position where completions are requested. |
        | `#!kotlin maxResults: Int` :material-information-outline:{ title="Default: 100" } | Maximum number of completion items to return. |
        | `#!kotlin kindFilter: List<SymbolKind>?` | Restrict results to these symbol kinds only. |

        #### Output: CompletionsResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin items: List<CompletionItem>` | Completion candidates available at the queried position. |
        | `#!kotlin exhaustive: Boolean` :material-information-outline:{ title="Default: true" } | True when all candidates were returned within maxResults. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast completions --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "completions",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 0
                    },
                    "maxResults": 10
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "items": [
                        {
                            "name": "greet",
                            "fqName": "sample.greet",
                            "kind": "FUNCTION",
                            "type": "String",
                            "parameters": [
                                {
                                    "name": "name",
                                    "type": "String",
                                    "isVararg": false
                                }
                            ],
                            "documentation": "/** Greets the provided name. */"
                        }
                    ],
                    "exhaustive": true,
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Results are bounded by `maxResults`. The `exhaustive` flag indicates whether all candidates were returned.
            - Use `kindFilter` to restrict results to specific symbol kinds.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

=== "Mutation operations"

    !!! abstract "At a glance"

        4 operations that modify workspace state: rename, optimize imports, apply edits, and refresh.

    ??? example "Rename"

        Plans a symbol rename by computing all text edits needed across the workspace. This is a dry-run by default — it returns edits without applying them.

        <div style="text-align:right">
        <code>RENAME</code>&ensp;<code>rename</code>
        </div>

        #### Input: RenameQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin position: FilePosition` | File position identifying the symbol to rename. |
        | `#!kotlin newName: String` | The new name to assign to the symbol. |
        | `#!kotlin dryRun: Boolean` :material-information-outline:{ title="Default: true" } | When true (default), computes edits without applying them. |

        #### Output: RenameResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin edits: List<TextEdit>` | Text edits needed to perform the rename across the workspace. |
        | `#!kotlin fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
        | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that would be modified. |
        | `#!kotlin searchScope: SearchScope?` | Describes the scope and exhaustiveness of the rename search. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast rename --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt --offset=42 --new-name=updatedName
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "rename",
                "params": {
                    "position": {
                        "filePath": "/workspace/src/Sample.kt",
                        "offset": 20
                    },
                    "newName": "welcome",
                    "dryRun": true
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "edits": [
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 20,
                            "endOffset": 25,
                            "newText": "welcome"
                        },
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 48,
                            "endOffset": 53,
                            "newText": "welcome"
                        }
                    ],
                    "fileHashes": [
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "hash": "fd31168346a51e49dbb21eca8e5d7cc897afe7116bb3ef21754f782ddb261f72"
                        }
                    ],
                    "affectedFiles": [
                        "/workspace/src/Sample.kt"
                    ],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - The result includes file hashes for conflict detection when applying edits later.
            - Pair with `edits/apply` to execute the rename after review.

        **Error codes:** `NOT_FOUND`

    ??? example "Optimize Imports"

        Optimizes imports for one or more files, removing unused imports and sorting the remainder.

        <div style="text-align:right">
        <code>OPTIMIZE_IMPORTS</code>&ensp;<code>imports/optimize</code>
        </div>

        #### Input: ImportOptimizeQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin filePaths: List<String>` | Absolute paths of the files whose imports should be optimized. |

        #### Output: ImportOptimizeResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin edits: List<TextEdit>` | Text edits that remove unused imports and sort the remainder. |
        | `#!kotlin fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
        | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that were modified. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast optimize-imports --workspace-root=/path/to/project --file=/path/to/project/src/main/kotlin/Example.kt
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "imports/optimize",
                "params": {
                    "filePaths": [
                        "/workspace/src/Sample.kt"
                    ]
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "edits": [],
                    "fileHashes": [],
                    "affectedFiles": [],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Returns the computed edits and file hashes. The daemon applies changes directly.

        **Error codes:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED`

    ??? example "Apply Edits"

        Applies a prepared edit plan with file-hash conflict detection. Pass the edits and hashes returned by a prior `rename` or other planning operation.

        <div style="text-align:right">
        <code>APPLY_EDITS</code>&ensp;<code>edits/apply</code>
        </div>

        #### Input: ApplyEditsQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin edits: List<TextEdit>` | Text edits to apply, typically from a prior rename or code action. |
        | `#!kotlin fileHashes: List<FileHash>` | Expected file hashes for conflict detection before writing. |
        | `#!kotlin fileOperations: List<FileOperation>` :material-information-outline:{ title="Default: emptyList()" } | Optional file create or delete operations to perform. |

        #### Output: ApplyEditsResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin applied: List<TextEdit>` | Text edits that were successfully applied. |
        | `#!kotlin affectedFiles: List<String>` | Absolute paths of all files that were modified. |
        | `#!kotlin createdFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files created by file operations. |
        | `#!kotlin deletedFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files deleted by file operations. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast apply-edits --workspace-root=/path/to/project --edits-json='{...}'
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "edits/apply",
                "params": {
                    "edits": [
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 0,
                            "endOffset": 0,
                            "newText": "// edited\n"
                        }
                    ],
                    "fileHashes": [
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "hash": "fd31168346a51e49dbb21eca8e5d7cc897afe7116bb3ef21754f782ddb261f72"
                        }
                    ],
                    "fileOperations": []
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "applied": [
                        {
                            "filePath": "/workspace/src/Sample.kt",
                            "startOffset": 0,
                            "endOffset": 0,
                            "newText": "// edited\n"
                        }
                    ],
                    "affectedFiles": [
                        "/workspace/src/Sample.kt"
                    ],
                    "createdFiles": [],
                    "deletedFiles": [],
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - File hashes are compared before writing. If a file changed since the edits were planned, the operation fails with a conflict error.
            - Supports optional `fileOperations` for creating or deleting files.

        **Error codes:** `CONFLICT`, `VALIDATION_ERROR`

    ??? example "Refresh Workspace"

        Forces the daemon to refresh its workspace state. Use this after external file modifications to ensure the daemon's view is current.

        <div style="text-align:right">
        <code>REFRESH_WORKSPACE</code>&ensp;<code>workspace/refresh</code>
        </div>

        #### Input: RefreshQuery

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin filePaths: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files to refresh. Empty for a full workspace refresh. |

        #### Output: RefreshResult

        | Signature | Description |
        |-----------|-------------|
        | `#!kotlin refreshedFiles: List<String>` | Absolute paths of files whose state was refreshed. |
        | `#!kotlin removedFiles: List<String>` :material-information-outline:{ title="Default: emptyList()" } | Absolute paths of files that were removed from the workspace. |
        | `#!kotlin fullRefresh: Boolean` | True when a full workspace refresh was performed. |
        | `#!kotlin schemaVersion: Int` | Protocol schema version for forward compatibility. |

        === "CLI example"

            ```bash
            kast workspace refresh --workspace-root=/path/to/project
            ```

        === "JSON-RPC request"

            ```json
            {
                "method": "workspace/refresh",
                "params": {
                    "filePaths": [
                        "/workspace/src/Sample.kt"
                    ]
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        === "Example response"

            ```json
            {
                "result": {
                    "refreshedFiles": [
                        "/workspace/src/Sample.kt"
                    ],
                    "removedFiles": [],
                    "fullRefresh": false,
                    "schemaVersion": 3
                },
                "id": 1,
                "jsonrpc": "2.0"
            }
            ```

        !!! note "Behavioral notes"

            - Pass specific file paths for a targeted refresh, or omit for a full workspace refresh.
            - The result reports which files were refreshed and which were removed.

        **Error codes:** `CAPABILITY_NOT_SUPPORTED`
