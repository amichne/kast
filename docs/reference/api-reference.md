---
title: API reference
---

# API reference

Complete reference for every JSON-RPC method in the Kast analysis
daemon, including input/output schemas, examples, and behavioral notes.

=== "System operations"

    !!! abstract "At a glance"

        3 operations for health checks, runtime status, and capability discovery. No capability gating required.

    ??? example "health — Basic health check"

        Returns a lightweight health check confirming the daemon is responsive. Use this before dispatching heavier queries.

        **Category:** system | **JSON-RPC method:** `health`

        #### Output: HealthResponse

        | Signature | Description |
        |-----------|-------------|
        | `status: String?` | Health status string, always "ok" when the daemon is responsive. |
        | `backendName: String` | Identifier of the analysis backend (e.g. "standalone" or "intellij"). |
        | `backendVersion: String` | Version string of the analysis backend. |
        | `workspaceRoot: String` | Absolute path of the workspace root directory. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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


    ??? example "runtime/status — Detailed runtime state including indexing progress"

        Returns the full runtime state including indexing progress, backend identity, and workspace root. Use this to verify readiness before running analysis commands.

        **Category:** system | **JSON-RPC method:** `runtime/status`

        #### Output: RuntimeStatusResponse

        | Signature | Description |
        |-----------|-------------|
        | `state: RuntimeState` | Current runtime state: STARTING, INDEXING, READY, or DEGRADED. |
        | `healthy: Boolean` | True when the daemon is responsive and not in an error state. |
        | `active: Boolean` | True when the daemon has an active workspace session. |
        | `indexing: Boolean` | True when the daemon is currently indexing the workspace. |
        | `backendName: String` | Identifier of the analysis backend. |
        | `backendVersion: String` | Version string of the analysis backend. |
        | `workspaceRoot: String` | Absolute path of the workspace root directory. |
        | `message: String?` | Human-readable status message with additional context. |
        | `warnings: List<String>?` | Active warning messages about the runtime environment. |
        | `sourceModuleNames: List<String>?` | Names of source modules discovered in the workspace. |
        | `dependentModuleNamesBySourceModuleName: Map<String, List<String>>?` | Map from source module name to its dependency module names. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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


    ??? example "capabilities — Advertised read and mutation capabilities"

        Lists every read and mutation capability the current backend advertises, along with server limits. Query this before calling an operation to confirm it is available.

        **Category:** system | **JSON-RPC method:** `capabilities`

        #### Output: BackendCapabilities

        | Signature | Description |
        |-----------|-------------|
        | `backendName: String` | Identifier of the analysis backend. |
        | `backendVersion: String` | Version string of the analysis backend. |
        | `workspaceRoot: String` | Absolute path of the workspace root directory. |
        | `readCapabilities: List<ReadCapability>` | Set of read operations this backend supports. |
        | `mutationCapabilities: List<MutationCapability>` | Set of mutation operations this backend supports. |
        | `limits: ServerLimits` | Server-enforced resource limits. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "symbol/resolve — Resolve the symbol at a file position"

        Resolves the symbol at a file position, returning its fully qualified name, kind, location, and optional metadata such as type information and documentation.

        **Capability:** `RESOLVE_SYMBOL` | **Category:** read | **JSON-RPC method:** `symbol/resolve`

        #### Input: SymbolQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position identifying the symbol to resolve. |
        | `includeDeclarationScope: Boolean?` | When true, populates the declarationScope field on the resolved symbol. |
        | `includeDocumentation: Boolean?` | When true, populates the documentation field on the resolved symbol. |

        #### Output: SymbolResult

        | Signature | Description |
        |-----------|-------------|
        | `symbol: Symbol` | The resolved symbol at the queried position. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "references — Find all references to the symbol at a file position"

        Finds all references to the symbol at a file position across the workspace. Optionally includes the declaration itself.

        **Capability:** `FIND_REFERENCES` | **Category:** read | **JSON-RPC method:** `references`

        #### Input: ReferencesQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position identifying the symbol whose references to find. |
        | `includeDeclaration: Boolean?` | When true, includes the symbol's own declaration in the results. |

        #### Output: ReferencesResult

        | Signature | Description |
        |-----------|-------------|
        | `declaration: Symbol?` | The resolved declaration symbol, included when `includeDeclaration` was set. |
        | `references: List<Location>` | List of source locations where the symbol is referenced. |
        | `page: PageInfo?` | Pagination metadata when results are truncated. |
        | `searchScope: SearchScope?` | Describes the scope and exhaustiveness of the search. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "call-hierarchy — Expand a bounded incoming or outgoing call tree"

        Expands a bounded incoming or outgoing call tree from a function or method. Use incoming to find callers, outgoing to find callees.

        **Capability:** `CALL_HIERARCHY` | **Category:** read | **JSON-RPC method:** `call-hierarchy`

        #### Input: CallHierarchyQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position identifying the function or method to expand. |
        | `direction: CallDirection` | INCOMING for callers or OUTGOING for callees. |
        | `depth: Int?` | Maximum tree depth to traverse. |
        | `maxTotalCalls: Int?` | Maximum total call nodes to return across the entire tree. |
        | `maxChildrenPerNode: Int?` | Maximum direct children per node before truncation. |
        | `timeoutMillis: Long?` | Optional timeout in milliseconds for the traversal. |

        #### Output: CallHierarchyResult

        | Signature | Description |
        |-----------|-------------|
        | `root: CallNode` | Root node of the call hierarchy tree. |
        | `stats: CallHierarchyStats` | Traversal statistics including truncation indicators. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "type-hierarchy — Expand supertypes and subtypes from a resolved symbol"

        Expands supertypes and subtypes from a resolved symbol. Use this to understand inheritance relationships.

        **Capability:** `TYPE_HIERARCHY` | **Category:** read | **JSON-RPC method:** `type-hierarchy`

        #### Input: TypeHierarchyQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position identifying the class or interface to expand. |
        | `direction: TypeHierarchyDirection?` | SUPERTYPES, SUBTYPES, or BOTH. |
        | `depth: Int?` | Maximum tree depth to traverse. |
        | `maxResults: Int?` | Maximum total nodes to return. |

        #### Output: TypeHierarchyResult

        | Signature | Description |
        |-----------|-------------|
        | `root: TypeHierarchyNode` | Root node of the type hierarchy tree. |
        | `stats: TypeHierarchyStats` | Traversal statistics including truncation indicators. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "semantic-insertion-point — Find the best insertion point for a new declaration"

        Finds the best insertion point for a new declaration relative to a file position. Use this to place generated code at a semantically appropriate location.

        **Capability:** `SEMANTIC_INSERTION_POINT` | **Category:** read | **JSON-RPC method:** `semantic-insertion-point`

        #### Input: SemanticInsertionQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position near the desired insertion location. |
        | `target: SemanticInsertionTarget` | Where to compute the insertion point relative to the position. |

        #### Output: SemanticInsertionResult

        | Signature | Description |
        |-----------|-------------|
        | `insertionOffset: Int` | Zero-based byte offset where new code should be inserted. |
        | `filePath: String` | Absolute path of the file containing the insertion point. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "diagnostics — Run compilation diagnostics for files"

        Runs compilation diagnostics for one or more files, returning errors, warnings, and informational messages with precise source locations.

        **Capability:** `DIAGNOSTICS` | **Category:** read | **JSON-RPC method:** `diagnostics`

        #### Input: DiagnosticsQuery

        | Signature | Description |
        |-----------|-------------|
        | `filePaths: List<String>` | Absolute paths of the files to analyze for diagnostics. |

        #### Output: DiagnosticsResult

        | Signature | Description |
        |-----------|-------------|
        | `diagnostics: List<Diagnostic>` | List of compilation diagnostics found in the requested files. |
        | `page: PageInfo?` | Pagination metadata when results are truncated. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "file-outline — Get a hierarchical symbol outline for a file"

        Returns a hierarchical symbol outline for a single file, listing all named declarations and their nesting.

        **Capability:** `FILE_OUTLINE` | **Category:** read | **JSON-RPC method:** `file-outline`

        #### Input: FileOutlineQuery

        | Signature | Description |
        |-----------|-------------|
        | `filePath: String` | Absolute path of the file to outline. |

        #### Output: FileOutlineResult

        | Signature | Description |
        |-----------|-------------|
        | `symbols: List<OutlineSymbol>` | Top-level symbols in the file, each containing nested children. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "workspace-symbol — Search the workspace for symbols by name pattern"

        Searches the entire workspace for symbols matching a name pattern. Supports substring matching and optional regex.

        **Capability:** `WORKSPACE_SYMBOL_SEARCH` | **Category:** read | **JSON-RPC method:** `workspace-symbol`

        #### Input: WorkspaceSymbolQuery

        | Signature | Description |
        |-----------|-------------|
        | `pattern: String` | Search pattern to match against symbol names. |
        | `kind: SymbolKind?` | Filter results to symbols of this kind only. |
        | `maxResults: Int?` | Maximum number of symbols to return. |
        | `regex: Boolean?` | When true, treats the pattern as a regular expression. |
        | `includeDeclarationScope: Boolean?` | When true, populates the declarationScope field on each matched symbol. |

        #### Output: WorkspaceSymbolResult

        | Signature | Description |
        |-----------|-------------|
        | `symbols: List<Symbol>` | Symbols matching the search pattern. |
        | `page: PageInfo?` | Pagination metadata when results are truncated. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "workspace/files — List workspace modules and source files"

        Lists workspace modules and their source files. Use this to discover the project structure visible to the daemon.

        **Capability:** `WORKSPACE_FILES` | **Category:** read | **JSON-RPC method:** `workspace/files`

        #### Input: WorkspaceFilesQuery

        | Signature | Description |
        |-----------|-------------|
        | `moduleName: String?` | Filter to a single module by name. Omit to list all modules. |
        | `includeFiles: Boolean?` | When true, includes individual file paths for each module. |

        #### Output: WorkspaceFilesResult

        | Signature | Description |
        |-----------|-------------|
        | `modules: List<WorkspaceModule>` | List of workspace modules visible to the daemon. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "implementations — Find concrete implementations and subclasses for a declaration"

        Finds concrete implementations and subclasses for an interface or abstract class declaration.

        **Capability:** `IMPLEMENTATIONS` | **Category:** read | **JSON-RPC method:** `implementations`

        #### Input: ImplementationsQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position identifying the interface or abstract class. |
        | `maxResults: Int?` | Maximum number of implementation symbols to return. |

        #### Output: ImplementationsResult

        | Signature | Description |
        |-----------|-------------|
        | `declaration: Symbol` | The interface or abstract class symbol that was queried. |
        | `implementations: List<Symbol>` | Concrete implementations or subclasses found. |
        | `exhaustive: Boolean?` | True when all implementations were found within maxResults. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "code-actions — Return available code actions at a file position"

        Returns available code actions at a file position, such as quick fixes and refactoring suggestions.

        **Capability:** `CODE_ACTIONS` | **Category:** read | **JSON-RPC method:** `code-actions`

        #### Input: CodeActionsQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position to query for available code actions. |
        | `diagnosticCode: String?` | Filter to actions that address this diagnostic code. |

        #### Output: CodeActionsResult

        | Signature | Description |
        |-----------|-------------|
        | `actions: List<CodeAction>` | Available code actions at the queried position. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "completions — Return completion candidates available at a file position"

        Returns completion candidates available at a file position. Use this to discover what symbols, keywords, or snippets the compiler suggests.

        **Capability:** `COMPLETIONS` | **Category:** read | **JSON-RPC method:** `completions`

        #### Input: CompletionsQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position where completions are requested. |
        | `maxResults: Int?` | Maximum number of completion items to return. |
        | `kindFilter: List<SymbolKind>?` | Restrict results to these symbol kinds only. |

        #### Output: CompletionsResult

        | Signature | Description |
        |-----------|-------------|
        | `items: List<CompletionItem>` | Completion candidates available at the queried position. |
        | `exhaustive: Boolean?` | True when all candidates were returned within maxResults. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "rename — Plan a symbol rename (dry-run by default)"

        Plans a symbol rename by computing all text edits needed across the workspace. This is a dry-run by default — it returns edits without applying them.

        **Capability:** `RENAME` | **Category:** mutation | **JSON-RPC method:** `rename`

        #### Input: RenameQuery

        | Signature | Description |
        |-----------|-------------|
        | `position: FilePosition` | File position identifying the symbol to rename. |
        | `newName: String` | The new name to assign to the symbol. |
        | `dryRun: Boolean?` | When true (default), computes edits without applying them. |

        #### Output: RenameResult

        | Signature | Description |
        |-----------|-------------|
        | `edits: List<TextEdit>` | Text edits needed to perform the rename across the workspace. |
        | `fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
        | `affectedFiles: List<String>` | Absolute paths of all files that would be modified. |
        | `searchScope: SearchScope?` | Describes the scope and exhaustiveness of the rename search. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "imports/optimize — Optimize imports for one or more files"

        Optimizes imports for one or more files, removing unused imports and sorting the remainder.

        **Capability:** `OPTIMIZE_IMPORTS` | **Category:** mutation | **JSON-RPC method:** `imports/optimize`

        #### Input: ImportOptimizeQuery

        | Signature | Description |
        |-----------|-------------|
        | `filePaths: List<String>` | Absolute paths of the files whose imports should be optimized. |

        #### Output: ImportOptimizeResult

        | Signature | Description |
        |-----------|-------------|
        | `edits: List<TextEdit>` | Text edits that remove unused imports and sort the remainder. |
        | `fileHashes: List<FileHash>` | File hashes at edit-plan time for conflict detection. |
        | `affectedFiles: List<String>` | Absolute paths of all files that were modified. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "edits/apply — Apply a prepared edit plan with conflict detection"

        Applies a prepared edit plan with file-hash conflict detection. Pass the edits and hashes returned by a prior `rename` or other planning operation.

        **Capability:** `APPLY_EDITS` | **Category:** mutation | **JSON-RPC method:** `edits/apply`

        #### Input: ApplyEditsQuery

        | Signature | Description |
        |-----------|-------------|
        | `edits: List<TextEdit>` | Text edits to apply, typically from a prior rename or code action. |
        | `fileHashes: List<FileHash>` | Expected file hashes for conflict detection before writing. |
        | `fileOperations: List<FileOperation>?` | Optional file create or delete operations to perform. |

        #### Output: ApplyEditsResult

        | Signature | Description |
        |-----------|-------------|
        | `applied: List<TextEdit>` | Text edits that were successfully applied. |
        | `affectedFiles: List<String>` | Absolute paths of all files that were modified. |
        | `createdFiles: List<String>?` | Absolute paths of files created by file operations. |
        | `deletedFiles: List<String>?` | Absolute paths of files deleted by file operations. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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

    ??? example "workspace/refresh — Force a targeted or full workspace state refresh"

        Forces the daemon to refresh its workspace state. Use this after external file modifications to ensure the daemon's view is current.

        **Capability:** `REFRESH_WORKSPACE` | **Category:** mutation | **JSON-RPC method:** `workspace/refresh`

        #### Input: RefreshQuery

        | Signature | Description |
        |-----------|-------------|
        | `filePaths: List<String>?` | Absolute paths of files to refresh. Empty for a full workspace refresh. |

        #### Output: RefreshResult

        | Signature | Description |
        |-----------|-------------|
        | `refreshedFiles: List<String>` | Absolute paths of files whose state was refreshed. |
        | `removedFiles: List<String>?` | Absolute paths of files that were removed from the workspace. |
        | `fullRefresh: Boolean` | True when a full workspace refresh was performed. |
        | `schemaVersion: Int?` | Protocol schema version for forward compatibility. |

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
