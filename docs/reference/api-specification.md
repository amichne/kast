---
title: API specification
description: Machine-readable OpenAPI 3.1 specification for Kast's raw
  analysis JSON-RPC protocol.
icon: lucide/file-code
---

The OpenAPI spec documents the backend-level `raw/*` analysis methods
plus the system methods `health`, `runtime/status`, and `capabilities`.
It's generated from the Kotlin serialization models in `analysis-api`
and stays in sync via automated tests.

For human-readable documentation of every OpenAPI operation — schemas,
examples, behavioral notes — see the [API reference](api-reference.md).

## Transport note

Real transport is **line-delimited JSON-RPC 2.0** over Unix domain
sockets, stdio pipes, or TCP — not HTTP. The OpenAPI spec is a
logical projection for docs, client codegen, and schema validation.
Batch requests and JSON-RPC notifications aren't supported.

## Full command catalog

`kast rpc` can also route higher-level `symbol/*` orchestration methods
and `database/*` index methods. The complete RPC command catalog lives in
`.agents/skills/kast/references/commands.json` and is packaged by the Rust
CLI in `kast-rs`.

Use OpenAPI when you need the raw backend schema. Use `commands.json`
when an agent or script needs the complete `kast rpc` catalog,
including `symbol/resolve`, `symbol/rename`, and `database/metrics`.

The embedded catalog below is generated from `commands.json` so readers can
browse the actual JSON-RPC suite directly on this page.

<!-- BEGIN GENERATED RPC CONTRACT SUITE -->
### Browse the JSON-RPC suite

This section is generated from `.agents/skills/kast/references/commands.json`
so the page exposes the same method catalog used by installed agent
skills and `kast rpc`. It embeds the command families, flow-oriented
building blocks, and request fields that callers compose into larger
automation flows.

Catalog version: `dev`. Methods: `29`.

#### Method families

The families below are the top-level namespaces accepted by `kast rpc`.

| Family | Role | Source | Methods |
| --- | --- | --- | --- |
| `system` | Runtime readiness, backend state, and capability discovery. | backend | `health`<br>`runtime/status`<br>`capabilities` |
| `symbol` | Name-based orchestration for agent and script workflows. | backend, sqlite | `symbol/scaffold`<br>`symbol/discover`<br>`symbol/query`<br>`symbol/resolve`<br>`symbol/references`<br>`symbol/callers`<br>`symbol/rename`<br>`symbol/write-and-validate` |
| `raw` | Position- and file-based backend primitives. | backend | `raw/resolve`<br>`raw/references`<br>`raw/call-hierarchy`<br>`raw/type-hierarchy`<br>`raw/semantic-insertion-point`<br>`raw/diagnostics`<br>`raw/rename`<br>`raw/optimize-imports`<br>`raw/apply-edits`<br>`raw/workspace-refresh`<br>`raw/file-outline`<br>`raw/workspace-symbol`<br>`raw/workspace-search`<br>`raw/workspace-files`<br>`raw/implementations`<br>`raw/code-actions`<br>`raw/completions` |
| `database` | Rust-owned SQLite source-index queries for metrics and impact views. | sqlite | `database/metrics` |

#### Composition building blocks

Use these groups as a starting point for composing multi-step flows.
Each method listed here is validated against the generated catalog.

| Block | Use it for | Methods |
| --- | --- | --- |
| Check runtime | Confirm the daemon is reachable, ready, and honest about supported work. | `health`<br>`runtime/status`<br>`capabilities` |
| Choose targets | List files, search symbols or text, and narrow ambiguous names before deeper calls. | `raw/workspace-files`<br>`raw/workspace-symbol`<br>`raw/workspace-search`<br>`symbol/resolve`<br>`raw/file-outline` |
| Inspect semantics | Resolve declarations, inspect scopes, and read implementation or completion context. | `raw/resolve`<br>`raw/semantic-insertion-point`<br>`raw/implementations`<br>`raw/code-actions`<br>`raw/completions` |
| Trace relationships | Move from one declaration to usages, callers, callees, and type relationships. | `symbol/references`<br>`raw/references`<br>`symbol/callers`<br>`raw/call-hierarchy`<br>`raw/type-hierarchy` |
| Plan changes | Ask Kast to derive edit plans or generation context before mutating files. | `symbol/scaffold`<br>`symbol/rename`<br>`raw/rename`<br>`raw/optimize-imports` |
| Apply and validate | Write prepared changes, refresh affected workspace state, and re-run diagnostics. | `symbol/write-and-validate`<br>`raw/apply-edits`<br>`raw/workspace-refresh`<br>`raw/diagnostics` |
| Read the index | Use the Rust source-index reader for coupling, dead-code, search, graph, and impact questions. | `database/metrics` |

#### Command catalog

The table below summarizes every method, its backing source, request
shape, response type, and success/failure variants when the method
uses a discriminated response envelope.

| Method | Family | Source | Summary | Required params | Optional params | Response | Variants |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `health` | `system` | backend | Basic health check | none | none | `HealthResponse` | single result |
| `runtime/status` | `system` | backend | Detailed runtime state including indexing progress | none | none | `RuntimeStatusResponse` | single result |
| `capabilities` | `system` | backend | Advertised read and mutation capabilities | none | none | `BackendCapabilities` | single result |
| `symbol/scaffold` | `symbol` | backend | Gather structural generation context for a Kotlin file | `targetFile` | `workspaceRoot`<br>`targetSymbol`<br>`mode`<br>`kind` | `KastScaffoldResponse` | `SCAFFOLD_SUCCESS`<br>`SCAFFOLD_FAILURE` |
| `symbol/discover` | `symbol` | backend | Rank candidate declarations for a simple symbol name | `symbol` | `workspaceRoot`<br>`fileHint`<br>`line`<br>`codeSnippet`<br>`kind`<br>`containingType`<br>`maxResults`<br>`includeDeclarationScope` | `KastDiscoverResponse` | `DISCOVER_SUCCESS`<br>`DISCOVER_FAILURE` |
| `symbol/query` | `symbol` | sqlite | Query compiler-indexed declarations with symbolic hard filters, fielded lexical/name matching, bounded graph relationship evidence, and optional semantic discovery evidence | `query` | `workspaceRoot`<br>`modes`<br>`filters`<br>`anchor`<br>`graph`<br>`semantic`<br>`limit`<br>`includeEvidence`<br>`includeNextRequests` | `KastSymbolQueryResponse` | `SYMBOL_QUERY_SUCCESS`<br>`SYMBOL_QUERY_FAILURE` |
| `symbol/resolve` | `symbol` | backend | Resolve a symbol by name to its declaration and optional context | `symbol` | `workspaceRoot`<br>`fileHint`<br>`kind`<br>`containingType`<br>`includeDeclarationScope`<br>`includeDocumentation`<br>`surroundingLines`<br>`includeSurroundingMembers` | `KastResolveResponse` | `RESOLVE_SUCCESS`<br>`RESOLVE_FAILURE` |
| `symbol/references` | `symbol` | backend | Find every usage of a Kotlin symbol | `symbol` | `workspaceRoot`<br>`fileHint`<br>`kind`<br>`containingType`<br>`includeDeclaration` | `KastReferencesResponse` | `REFERENCES_SUCCESS`<br>`REFERENCES_FAILURE` |
| `symbol/callers` | `symbol` | backend | Expand an incoming or outgoing call hierarchy | `symbol` | `workspaceRoot`<br>`fileHint`<br>`kind`<br>`containingType`<br>`direction`<br>`depth`<br>`maxTotalCalls`<br>`maxChildrenPerNode`<br>`timeoutMillis` | `KastCallersResponse` | `CALLERS_SUCCESS`<br>`CALLERS_FAILURE` |
| `symbol/rename` | `symbol` | backend | Resolve or target a symbol and apply a rename | `type` | none | `KastRenameResponse` | `RENAME_SUCCESS`<br>`RENAME_FAILURE` |
| `symbol/write-and-validate` | `symbol` | backend | Apply generated Kotlin code and validate the result | `type` | none | `KastWriteAndValidateResponse` | `WRITE_AND_VALIDATE_SUCCESS`<br>`WRITE_AND_VALIDATE_FAILURE` |
| `raw/resolve` | `raw` | backend | Resolve the symbol at a file position | `position` | `includeDeclarationScope`<br>`includeDocumentation` | `SymbolResult` | single result |
| `raw/references` | `raw` | backend | Find all references to the symbol at a file position | `position` | `includeDeclaration`<br>`includeUsageSiteScope` | `ReferencesResult` | single result |
| `raw/call-hierarchy` | `raw` | backend | Expand a bounded incoming or outgoing call tree | `position`<br>`direction` | `depth`<br>`maxTotalCalls`<br>`maxChildrenPerNode`<br>`timeoutMillis` | `CallHierarchyResult` | single result |
| `raw/type-hierarchy` | `raw` | backend | Expand supertypes and subtypes from a resolved symbol | `position` | `direction`<br>`depth`<br>`maxResults` | `TypeHierarchyResult` | single result |
| `raw/semantic-insertion-point` | `raw` | backend | Find the best insertion point for a new declaration | `position`<br>`target` | none | `SemanticInsertionResult` | single result |
| `raw/diagnostics` | `raw` | backend | Run Kotlin diagnostics on listed files | `filePaths` | none | `DiagnosticsResult` | single result |
| `raw/rename` | `raw` | backend | Plan a symbol rename by file position | `position`<br>`newName` | `dryRun` | `RenameResult` | single result |
| `raw/optimize-imports` | `raw` | backend | Optimize imports for one or more files | `filePaths` | none | `ImportOptimizeResult` | single result |
| `raw/apply-edits` | `raw` | backend | Apply a prepared edit plan with conflict detection | `edits`<br>`fileHashes` | `fileOperations` | `ApplyEditsResult` | single result |
| `raw/workspace-refresh` | `raw` | backend | Force a targeted or full workspace state refresh | none | `filePaths` | `RefreshResult` | single result |
| `raw/file-outline` | `raw` | backend | Get a hierarchical symbol outline for a file | `filePath` | none | `FileOutlineResult` | single result |
| `raw/workspace-symbol` | `raw` | backend | Search the workspace for symbols by name pattern | `pattern` | `kind`<br>`maxResults`<br>`regex`<br>`includeDeclarationScope` | `WorkspaceSymbolResult` | single result |
| `raw/workspace-search` | `raw` | backend | Search workspace file contents by text or regex | `pattern` | `regex`<br>`maxResults`<br>`fileGlob`<br>`caseSensitive` | `WorkspaceSearchResult` | single result |
| `raw/workspace-files` | `raw` | backend | List workspace modules and optional file paths | none | `moduleName`<br>`includeFiles`<br>`maxFilesPerModule` | `WorkspaceFilesResult` | single result |
| `raw/implementations` | `raw` | backend | Find concrete implementations and subclasses for a declaration | `position` | `maxResults` | `ImplementationsResult` | single result |
| `raw/code-actions` | `raw` | backend | Return available code actions at a file position | `position` | `diagnosticCode` | `CodeActionsResult` | single result |
| `raw/completions` | `raw` | backend | Return completion candidates available at a file position | `position` | `maxResults`<br>`kindFilter` | `CompletionsResult` | single result |
| `database/metrics` | `database` | sqlite | Query Rust-owned source-index metrics | `metric` | `workspaceRoot`<br>`limit`<br>`symbol`<br>`depth`<br>`fileGlob`<br>`folderFilter` | `RustMetricsResponse` | `METRICS_SUCCESS`<br>`METRICS_FAILURE` |

#### Command field details

Open a method to inspect the request fields declared in the catalog.

<details markdown="1">
<summary><code>health</code> - Basic health check</summary>

No request parameters.

Response type: `HealthResponse`.

</details>

<details markdown="1">
<summary><code>runtime/status</code> - Detailed runtime state including indexing progress</summary>

No request parameters.

Response type: `RuntimeStatusResponse`.

</details>

<details markdown="1">
<summary><code>capabilities</code> - Advertised read and mutation capabilities</summary>

No request parameters.

Response type: `BackendCapabilities`.

</details>

<details markdown="1">
<summary><code>symbol/scaffold</code> - Gather structural generation context for a Kotlin file</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `targetFile` | `string` | yes | no |  |
| `targetSymbol` | `string` | no | yes |  |
| `mode` | `string` | no | no | `implement`<br>`replace`<br>`consolidate`<br>`extract` |
| `kind` | `string` | no | yes | `class`<br>`interface`<br>`object`<br>`function`<br>`property` |

Response type: `KastScaffoldResponse`.
Result variants: `SCAFFOLD_SUCCESS`, `SCAFFOLD_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/discover</code> - Rank candidate declarations for a simple symbol name</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `symbol` | `string` | yes | no |  |
| `fileHint` | `string` | no | yes |  |
| `line` | `integer` | no | yes |  |
| `codeSnippet` | `string` | no | yes |  |
| `kind` | `string` | no | yes | `class`<br>`interface`<br>`object`<br>`function`<br>`property` |
| `containingType` | `string` | no | yes |  |
| `maxResults` | `integer` | no | no |  |
| `includeDeclarationScope` | `boolean` | no | no |  |

Response type: `KastDiscoverResponse`.
Result variants: `DISCOVER_SUCCESS`, `DISCOVER_FAILURE`.

Notes:

- Use this before symbol/resolve when a simple name is ambiguous or context is available.
- Candidates include resolveParams and nextRequest fields that can be sent to symbol/resolve.

</details>

<details markdown="1">
<summary><code>symbol/query</code> - Query compiler-indexed declarations with symbolic hard filters, fielded lexical/name matching, bounded graph relationship evidence, and optional semantic discovery evidence</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `query` | `string` | yes | no |  |
| `modes` | `array of {'type': 'string', 'enum': ['exact', 'lexical', 'structural', 'graph', 'semantic']}` | no | no |  |
| `filters` | `object` | no | no |  |
| `anchor` | `object` | no | no |  |
| `graph` | `object` | no | no |  |
| `semantic` | `object` | no | no |  |
| `limit` | `integer` | no | no |  |
| `includeEvidence` | `boolean` | no | no |  |
| `includeNextRequests` | `boolean` | no | no |  |

Response type: `KastSymbolQueryResponse`.
Result variants: `SYMBOL_QUERY_SUCCESS`, `SYMBOL_QUERY_FAILURE`.

Notes:

- Handled by the Rust CLI before daemon passthrough; JVM backends do not read this SQLite surface.
- Hard filters are enforced by SQLite/compiler facts, never by semantic score.
- Graph depth defaults to 1 and is capped at 2 in the first implementation.
- Semantic discovery is represented in the response shape but reports available=false until a sidecar exists.

</details>

<details markdown="1">
<summary><code>symbol/resolve</code> - Resolve a symbol by name to its declaration and optional context</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `symbol` | `string` | yes | no |  |
| `fileHint` | `string` | no | yes |  |
| `kind` | `string` | no | yes | `class`<br>`interface`<br>`object`<br>`function`<br>`property` |
| `containingType` | `string` | no | yes |  |
| `includeDeclarationScope` | `boolean` | no | no |  |
| `includeDocumentation` | `boolean` | no | no |  |
| `surroundingLines` | `integer` | no | yes |  |
| `includeSurroundingMembers` | `boolean` | no | no |  |

Response type: `KastResolveResponse`.
Result variants: `RESOLVE_SUCCESS`, `RESOLVE_FAILURE`.

Notes:

- The 'symbol' field takes simple names only (e.g. 'key'), never fully-qualified names.
- Use 'containingType' for scoping and 'fileHint' for disambiguation.
- Set includeDeclarationScope, includeDocumentation, surroundingLines, or includeSurroundingMembers only when the extra context is needed.

</details>

<details markdown="1">
<summary><code>symbol/references</code> - Find every usage of a Kotlin symbol</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `symbol` | `string` | yes | no |  |
| `fileHint` | `string` | no | yes |  |
| `kind` | `string` | no | yes | `class`<br>`interface`<br>`object`<br>`function`<br>`property` |
| `containingType` | `string` | no | yes |  |
| `includeDeclaration` | `boolean` | no | no |  |

Response type: `KastReferencesResponse`.
Result variants: `REFERENCES_SUCCESS`, `REFERENCES_FAILURE`.

Notes:

- The 'symbol' field takes simple names only.
- Resolve ambiguous names first with 'kind', 'containingType', or 'fileHint'.

</details>

<details markdown="1">
<summary><code>symbol/callers</code> - Expand an incoming or outgoing call hierarchy</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `symbol` | `string` | yes | no |  |
| `fileHint` | `string` | no | yes |  |
| `kind` | `string` | no | yes | `class`<br>`interface`<br>`object`<br>`function`<br>`property` |
| `containingType` | `string` | no | yes |  |
| `direction` | `string` | no | no | `incoming`<br>`outgoing` |
| `depth` | `integer` | no | no |  |
| `maxTotalCalls` | `integer` | no | yes |  |
| `maxChildrenPerNode` | `integer` | no | yes |  |
| `timeoutMillis` | `integer` | no | yes |  |

Response type: `KastCallersResponse`.
Result variants: `CALLERS_SUCCESS`, `CALLERS_FAILURE`.

Notes:

- The 'symbol' field takes simple names only.

</details>

<details markdown="1">
<summary><code>symbol/rename</code> - Resolve or target a symbol and apply a rename</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `type` | `string` | yes | no | `RENAME_BY_SYMBOL_REQUEST`<br>`RENAME_BY_OFFSET_REQUEST` |

Response type: `KastRenameResponse`.
Result variants: `RENAME_SUCCESS`, `RENAME_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/write-and-validate</code> - Apply generated Kotlin code and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `type` | `string` | yes | no | `CREATE_FILE_REQUEST`<br>`INSERT_AT_OFFSET_REQUEST`<br>`REPLACE_RANGE_REQUEST` |

Response type: `KastWriteAndValidateResponse`.
Result variants: `WRITE_AND_VALIDATE_SUCCESS`, `WRITE_AND_VALIDATE_FAILURE`.

</details>

<details markdown="1">
<summary><code>raw/resolve</code> - Resolve the symbol at a file position</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `includeDeclarationScope` | `boolean` | no | no |  |
| `includeDocumentation` | `boolean` | no | no |  |

Response type: `SymbolResult`.

</details>

<details markdown="1">
<summary><code>raw/references</code> - Find all references to the symbol at a file position</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `includeDeclaration` | `boolean` | no | no |  |
| `includeUsageSiteScope` | `boolean` | no | no |  |

Response type: `ReferencesResult`.

</details>

<details markdown="1">
<summary><code>raw/call-hierarchy</code> - Expand a bounded incoming or outgoing call tree</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `direction` | `string` | yes | no | `INCOMING`<br>`OUTGOING` |
| `depth` | `integer` | no | no |  |
| `maxTotalCalls` | `integer` | no | no |  |
| `maxChildrenPerNode` | `integer` | no | no |  |
| `timeoutMillis` | `integer` | no | yes |  |

Response type: `CallHierarchyResult`.

</details>

<details markdown="1">
<summary><code>raw/type-hierarchy</code> - Expand supertypes and subtypes from a resolved symbol</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `direction` | `string` | no | no | `SUPERTYPES`<br>`SUBTYPES`<br>`BOTH` |
| `depth` | `integer` | no | no |  |
| `maxResults` | `integer` | no | no |  |

Response type: `TypeHierarchyResult`.

</details>

<details markdown="1">
<summary><code>raw/semantic-insertion-point</code> - Find the best insertion point for a new declaration</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `target` | `string` | yes | no | `CLASS_BODY_START`<br>`CLASS_BODY_END`<br>`FILE_TOP`<br>`FILE_BOTTOM`<br>`AFTER_IMPORTS` |

Response type: `SemanticInsertionResult`.

</details>

<details markdown="1">
<summary><code>raw/diagnostics</code> - Run Kotlin diagnostics on listed files</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `filePaths` | `array of string` | yes | no |  |

Response type: `DiagnosticsResult`.

</details>

<details markdown="1">
<summary><code>raw/rename</code> - Plan a symbol rename by file position</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `newName` | `string` | yes | no |  |
| `dryRun` | `boolean` | no | no |  |

Response type: `RenameResult`.

</details>

<details markdown="1">
<summary><code>raw/optimize-imports</code> - Optimize imports for one or more files</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `filePaths` | `array of string` | yes | no |  |

Response type: `ImportOptimizeResult`.

</details>

<details markdown="1">
<summary><code>raw/apply-edits</code> - Apply a prepared edit plan with conflict detection</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `edits` | `array of object` | yes | no |  |
| `fileHashes` | `array of object` | yes | no |  |
| `fileOperations` | `array of object` | no | no |  |

Response type: `ApplyEditsResult`.

</details>

<details markdown="1">
<summary><code>raw/workspace-refresh</code> - Force a targeted or full workspace state refresh</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `filePaths` | `array of string` | no | no |  |

Response type: `RefreshResult`.

</details>

<details markdown="1">
<summary><code>raw/file-outline</code> - Get a hierarchical symbol outline for a file</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `filePath` | `string` | yes | no |  |

Response type: `FileOutlineResult`.

</details>

<details markdown="1">
<summary><code>raw/workspace-symbol</code> - Search the workspace for symbols by name pattern</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `pattern` | `string` | yes | no |  |
| `kind` | `string` | no | yes | `CLASS`<br>`INTERFACE`<br>`OBJECT`<br>`FUNCTION`<br>`PROPERTY`<br>`PARAMETER`<br>`UNKNOWN` |
| `maxResults` | `integer` | no | no |  |
| `regex` | `boolean` | no | no |  |
| `includeDeclarationScope` | `boolean` | no | no |  |

Response type: `WorkspaceSymbolResult`.

</details>

<details markdown="1">
<summary><code>raw/workspace-search</code> - Search workspace file contents by text or regex</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `pattern` | `string` | yes | no |  |
| `regex` | `boolean` | no | no |  |
| `maxResults` | `integer` | no | no |  |
| `fileGlob` | `string` | no | yes |  |
| `caseSensitive` | `boolean` | no | no |  |

Response type: `WorkspaceSearchResult`.

</details>

<details markdown="1">
<summary><code>raw/workspace-files</code> - List workspace modules and optional file paths</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `moduleName` | `string` | no | yes |  |
| `includeFiles` | `boolean` | no | no |  |
| `maxFilesPerModule` | `integer` | no | yes |  |

Response type: `WorkspaceFilesResult`.

</details>

<details markdown="1">
<summary><code>raw/implementations</code> - Find concrete implementations and subclasses for a declaration</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `maxResults` | `integer` | no | no |  |

Response type: `ImplementationsResult`.

</details>

<details markdown="1">
<summary><code>raw/code-actions</code> - Return available code actions at a file position</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `diagnosticCode` | `string` | no | yes |  |

Response type: `CodeActionsResult`.

</details>

<details markdown="1">
<summary><code>raw/completions</code> - Return completion candidates available at a file position</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `position` | `object` | yes | no |  |
| `maxResults` | `integer` | no | no |  |
| `kindFilter` | `array of string` | no | yes |  |

Response type: `CompletionsResult`.

</details>

<details markdown="1">
<summary><code>database/metrics</code> - Query Rust-owned source-index metrics</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `metric` | `string` | yes | no | `fanIn`<br>`fanOut`<br>`deadCode`<br>`impact`<br>`coupling`<br>`search`<br>`graph` |
| `limit` | `integer` | no | no |  |
| `symbol` | `string` | no | yes |  |
| `depth` | `integer` | no | no |  |
| `fileGlob` | `string` | no | yes |  |
| `folderFilter` | `string` | no | yes |  |

Response type: `RustMetricsResponse`.
Result variants: `METRICS_SUCCESS`, `METRICS_FAILURE`.

Notes:

- Handled by the Rust CLI before daemon passthrough; JVM backends do not read this SQLite surface.
- Use fanIn, fanOut, deadCode, impact, coupling, search, or graph for the v1 Rust metrics reader.

</details>

<!-- END GENERATED RPC CONTRACT SUITE -->

## Capability gating

Read and mutation operations require the daemon to advertise the
matching capability via the `capabilities` method. Each operation
in the spec includes an `x-kast-required-capability` extension
naming the required capability enum value (e.g. `RESOLVE_SYMBOL`,
`RENAME`). System methods have no capability requirement.

`raw/apply-edits` additionally needs the `FILE_OPERATIONS` capability
when the request carries non-empty `fileOperations`. This
conditional requirement is documented with the
`x-kast-conditional-capability` extension.

## View the spec

The generated YAML is checked into `docs/openapi.yaml` in the repository root
and served alongside these docs on GitHub Pages.

[:material-file-code: View openapi.yaml](../openapi.yaml){ .md-button }

## Download as build artifact

The OpenAPI spec is published as `dist/openapi.yaml` alongside the CLI and
plugin artifacts when you run `./kast.sh build`. You can also generate it directly:

```console
./gradlew :analysis-api:generateOpenApiSpec
```

## Import into tools

Valid OpenAPI 3.1. Import into Swagger UI, Redoc, or Stoplight, or
use it for client codegen with openapi-generator. The
`jsonrpc://localhost` server URL is a logical placeholder —
configure your client for the real transport.

## Schema version

The spec version tracks the analysis API schema version
(`SCHEMA_VERSION`), currently **3**. OpenAPI `info.version` is set
to `3.0.0` to reflect this.

??? info "For contributors: regenerating the spec"

    To regenerate the checked-in YAML after changing analysis-api models:

    ```console
    ./gradlew :analysis-api:generateOpenApiSpec
    ```

    The `AnalysisOpenApiDocumentTest` will fail if the checked-in file drifts
    from the generated output, ensuring the spec stays in sync with the Kotlin
    models.
