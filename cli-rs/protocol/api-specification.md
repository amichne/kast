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

## Internal JSON-RPC catalog

Typed `kast agent` commands route through higher-level `symbol/*`
orchestration methods and `database/*` index methods. The complete internal
method catalog lives in `cli-rs/protocol/source/commands.json`
and is packaged by the Rust CLI in `cli-rs/`.

Use OpenAPI when you need the raw backend schema. Use `commands.json`
when maintaining typed agent commands, installed skills, or internal protocol
contracts that cover `symbol/resolve`, `symbol/rename`, and
`database/metrics`.

The embedded catalog below is generated from `commands.json` so maintainers
can browse the JSON-RPC suite directly on this page.

<!-- BEGIN GENERATED RPC CONTRACT SUITE -->
### Browse the JSON-RPC suite

This section is generated from `cli-rs/protocol/source/commands.json`
so the page exposes the internal JSON-RPC catalog used by typed
`kast agent` commands and installed skills. It embeds the command
families, flow-oriented building blocks, and request fields that
callers compose into larger automation flows.

Catalog version: `dev`. Methods: `42`.

#### Method families

The families below are internal JSON-RPC namespaces, not public CLI commands.

| Family | Role | Source | Methods |
| --- | --- | --- | --- |
| `system` | Runtime readiness, backend state, and capability discovery. | backend | `health`<br>`runtime/status`<br>`runtime/shutdown`<br>`runtime/restart`<br>`capabilities` |
| `mutation` | Cataloged JSON-RPC methods. | backend | `mutation/submit` |
| `symbol` | Name-based orchestration for agent and script workflows. | backend, sqlite | `symbol/scaffold`<br>`symbol/discover`<br>`symbol/query`<br>`symbol/resolve`<br>`selector/identity`<br>`symbol/references`<br>`symbol/callers`<br>`symbol/implementations`<br>`symbol/hierarchy`<br>`symbol/rename`<br>`symbol/write-and-validate`<br>`symbol/add-file`<br>`symbol/add-declaration`<br>`symbol/add-implementation`<br>`symbol/add-statement`<br>`symbol/replace-declaration` |
| `raw` | Position- and file-based backend primitives. | backend | `raw/resolve`<br>`raw/references`<br>`raw/call-hierarchy`<br>`raw/type-hierarchy`<br>`raw/semantic-insertion-point`<br>`raw/diagnostics`<br>`raw/rename`<br>`raw/optimize-imports`<br>`raw/apply-edits`<br>`raw/workspace-refresh`<br>`raw/file-outline`<br>`raw/workspace-symbol`<br>`raw/workspace-search`<br>`raw/workspace-files`<br>`raw/semantic-graph`<br>`raw/workspace-files-continuation`<br>`raw/implementations`<br>`raw/code-actions`<br>`raw/completions` |
| `database` | Source-index queries for metrics and impact views. | sqlite | `database/metrics` |

#### Composition building blocks

Use these groups as a starting point for composing multi-step flows.
Each method listed here is validated against the generated catalog.

| Block | Use it for | Methods |
| --- | --- | --- |
| Check runtime | Confirm the daemon is reachable, ready, and honest about supported work. | `health`<br>`runtime/status`<br>`capabilities` |
| Choose targets | Query indexed declarations or bounded symbol/text searches before optional workspace file inspection. | `symbol/query`<br>`raw/workspace-symbol`<br>`raw/workspace-search`<br>`raw/workspace-files`<br>`symbol/resolve`<br>`raw/file-outline` |
| Inspect semantics | Resolve declarations, inspect scopes, and read implementation or completion context. | `raw/resolve`<br>`raw/semantic-insertion-point`<br>`raw/implementations`<br>`raw/code-actions`<br>`raw/completions` |
| Trace relationships | Move from one declaration to usages, callers, callees, and type relationships. | `symbol/references`<br>`raw/references`<br>`symbol/callers`<br>`raw/call-hierarchy`<br>`raw/type-hierarchy` |
| Plan changes | Ask Kast to derive edit plans or generation context before mutating files. | `symbol/scaffold`<br>`symbol/rename`<br>`raw/rename`<br>`raw/optimize-imports` |
| Apply and validate | Write prepared changes, refresh affected workspace state, and re-run diagnostics. | `symbol/write-and-validate`<br>`raw/apply-edits`<br>`raw/workspace-refresh`<br>`raw/diagnostics` |
| Read the index | Use the source-index metrics reader for coupling, dead-code, search, graph, and impact questions. | `database/metrics` |

#### Command catalog

The table below summarizes every method, its backing source, request
shape, response type, and success/failure variants when the method
uses a discriminated response envelope.

| Method | Family | Source | Summary | Required params | Optional params | Response | Variants |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `health` | `system` | backend | Basic health check | none | none | `HealthResponse` | single result |
| `runtime/status` | `system` | backend | Detailed runtime state including indexing progress | none | none | `RuntimeStatusResponse` | single result |
| `runtime/shutdown` | `system` | backend | Ask the runtime host to shut down after this response is flushed | none | none | `RuntimeLifecycleResponse` | single result |
| `runtime/restart` | `system` | backend | Ask the runtime host to restart after this response is flushed | none | none | `RuntimeLifecycleResponse` | single result |
| `capabilities` | `system` | backend | Advertised read and mutation capabilities | none | none | `BackendCapabilities` | single result |
| `mutation/submit` | `mutation` | backend | Execute an idempotent semantic mutation and return its terminal result | `type`<br>`RENAME`: `idempotencyKey`, `request`<br>`ADD_FILE`: `idempotencyKey`, `request`<br>`ADD_DECLARATION`: `idempotencyKey`, `request`<br>`ADD_IMPLEMENTATION`: `idempotencyKey`, `request`<br>`ADD_STATEMENT`: `idempotencyKey`, `request`<br>`REPLACE_DECLARATION`: `idempotencyKey`, `request` | none | `KastMutationExecutionResult` | single result |
| `symbol/scaffold` | `symbol` | backend | Gather structural generation context for a Kotlin file | `targetFile` | `workspaceRoot`<br>`targetSymbol`<br>`mode`<br>`kind` | `KastScaffoldResponse` | `SCAFFOLD_SUCCESS`<br>`SCAFFOLD_FAILURE` |
| `symbol/discover` | `symbol` | backend | Rank candidate declarations for a simple symbol name | `symbol` | `workspaceRoot`<br>`fileHint`<br>`line`<br>`codeSnippet`<br>`kind`<br>`containingType`<br>`maxResults`<br>`includeDeclarationScope` | `KastDiscoverResponse` | `DISCOVER_SUCCESS`<br>`DISCOVER_FAILURE` |
| `symbol/query` | `symbol` | sqlite | Query compiler-indexed declarations with symbolic hard filters, fielded lexical/name matching, bounded graph relationship evidence, and optional semantic discovery evidence | `query` | `workspaceRoot`<br>`modes`<br>`filters`<br>`anchor`<br>`graph`<br>`semantic`<br>`limit`<br>`includeEvidence`<br>`includeNextRequests` | `KastSymbolQueryResponse` | `SYMBOL_QUERY_SUCCESS`<br>`SYMBOL_QUERY_FAILURE` |
| `symbol/resolve` | `symbol` | backend | Resolve an exact simple or fully-qualified symbol identity with hard constraints and typed expected outcomes | `symbol` | `workspaceRoot`<br>`fileHint`<br>`kind`<br>`containingType`<br>`includeDeclarationScope`<br>`includeDocumentation`<br>`surroundingLines`<br>`includeSurroundingMembers` | `KastResolveResponse` | `RESOLVE_SUCCESS`<br>`RESOLVE_NOT_FOUND`<br>`RESOLVE_AMBIGUOUS`<br>`RESOLVE_FAILURE` |
| `selector/identity` | `symbol` | backend | Authenticate an opaque selector handle for one operation family and recover its compact exact identity | `selectorHandle`<br>`family` | `workspaceRoot` | `KastSelectorIdentityResponse` | `AVAILABLE`<br>`SELECTOR_HANDLE_REJECTED` |
| `symbol/references` | `symbol` | backend | Find every usage of a Kotlin symbol | none | `workspaceRoot`<br>`selectorHandle`<br>`selector`<br>`includeDeclaration`<br>`includeUsageSiteScope`<br>`maxResults`<br>`pageToken` | `KastReferencesResponse` | `AVAILABLE`<br>`SUBJECT_NOT_FOUND`<br>`SUBJECT_IDENTITY_MISMATCH`<br>`UNSUPPORTED_SUBJECT_KIND`<br>`DEGRADED`<br>`CURSOR_STALE`<br>`CURSOR_INVALID`<br>`SELECTOR_HANDLE_REJECTED` |
| `symbol/callers` | `symbol` | backend | Page exact incoming or outgoing call relationships | `direction` | `workspaceRoot`<br>`selectorHandle`<br>`selector`<br>`depth`<br>`maxResults`<br>`pageToken` | `KastCallersResponse` | `AVAILABLE`<br>`SUBJECT_NOT_FOUND`<br>`SUBJECT_IDENTITY_MISMATCH`<br>`UNSUPPORTED_SUBJECT_KIND`<br>`DEGRADED`<br>`CURSOR_STALE`<br>`CURSOR_INVALID`<br>`SELECTOR_HANDLE_REJECTED` |
| `symbol/implementations` | `symbol` | backend | Page exact implementation relationships | none | `workspaceRoot`<br>`selectorHandle`<br>`selector`<br>`maxResults`<br>`pageToken` | `KastImplementationsResponse` | `AVAILABLE`<br>`SUBJECT_NOT_FOUND`<br>`SUBJECT_IDENTITY_MISMATCH`<br>`UNSUPPORTED_SUBJECT_KIND`<br>`DEGRADED`<br>`CURSOR_STALE`<br>`CURSOR_INVALID`<br>`SELECTOR_HANDLE_REJECTED` |
| `symbol/hierarchy` | `symbol` | backend | Page exact type hierarchy relationships | `direction` | `workspaceRoot`<br>`selectorHandle`<br>`selector`<br>`depth`<br>`maxResults`<br>`pageToken` | `KastHierarchyResponse` | `AVAILABLE`<br>`SUBJECT_NOT_FOUND`<br>`SUBJECT_IDENTITY_MISMATCH`<br>`UNSUPPORTED_SUBJECT_KIND`<br>`DEGRADED`<br>`CURSOR_STALE`<br>`CURSOR_INVALID`<br>`SELECTOR_HANDLE_REJECTED` |
| `symbol/rename` | `symbol` | backend | Resolve or target a symbol and apply a rename | `type`<br>`RENAME_BY_SYMBOL_REQUEST`: `symbol`, `newName`<br>`RENAME_BY_OFFSET_REQUEST`: `filePath`, `offset`, `newName` | none | `KastRenameResponse` | `RENAME_SUCCESS`<br>`RENAME_FAILURE` |
| `symbol/write-and-validate` | `symbol` | backend | Apply generated Kotlin code and validate the result | `type`<br>`CREATE_FILE_REQUEST`: `filePath`<br>`INSERT_AT_OFFSET_REQUEST`: `filePath`, `offset`<br>`REPLACE_RANGE_REQUEST`: `filePath`, `startOffset`, `endOffset` | none | `KastWriteAndValidateResponse` | `WRITE_AND_VALIDATE_SUCCESS`<br>`WRITE_AND_VALIDATE_FAILURE` |
| `symbol/add-file` | `symbol` | backend | Create a Kotlin file from a content file and validate the result | `filePath`<br>`contentFile` | `workspaceRoot` | `KastScopeMutationResponse` | `SCOPE_MUTATION_SUCCESS`<br>`SCOPE_MUTATION_FAILURE` |
| `symbol/add-declaration` | `symbol` | backend | Insert declaration content into a file or named Kotlin scope and validate the result | `placement`<br>`contentFile` | `workspaceRoot` | `KastScopeMutationResponse` | `SCOPE_MUTATION_SUCCESS`<br>`SCOPE_MUTATION_FAILURE` |
| `symbol/add-implementation` | `symbol` | backend | Insert implementation content into a file or named Kotlin scope and validate the result | `placement`<br>`contentFile` | `workspaceRoot` | `KastScopeMutationResponse` | `SCOPE_MUTATION_SUCCESS`<br>`SCOPE_MUTATION_FAILURE` |
| `symbol/add-statement` | `symbol` | backend | Insert statement content into a named executable Kotlin scope and validate the result | `insideScope`<br>`anchor`<br>`contentFile` | `workspaceRoot` | `KastScopeMutationResponse` | `SCOPE_MUTATION_SUCCESS`<br>`SCOPE_MUTATION_FAILURE` |
| `symbol/replace-declaration` | `symbol` | backend | Replace a named Kotlin declaration using declaration-scope evidence and validate the result | `symbol`<br>`contentFile` | `workspaceRoot`<br>`fileHint`<br>`kind`<br>`containingType` | `KastScopeMutationResponse` | `SCOPE_MUTATION_SUCCESS`<br>`SCOPE_MUTATION_FAILURE` |
| `raw/resolve` | `raw` | backend | Resolve the symbol at a file position | `position` | `includeDeclarationScope`<br>`includeDocumentation` | `SymbolResult` | single result |
| `raw/references` | `raw` | backend | Find all references to the symbol at a file position | `position` | `includeDeclaration`<br>`includeUsageSiteScope`<br>`maxResults`<br>`pageToken` | `ReferencesResult` | single result |
| `raw/call-hierarchy` | `raw` | backend | Expand a bounded incoming or outgoing call tree | `position`<br>`direction` | `depth`<br>`maxTotalCalls`<br>`maxChildrenPerNode`<br>`timeoutMillis` | `CallHierarchyResult` | single result |
| `raw/type-hierarchy` | `raw` | backend | Expand supertypes and subtypes from a resolved symbol | `position` | `direction`<br>`depth`<br>`maxResults` | `TypeHierarchyResult` | single result |
| `raw/semantic-insertion-point` | `raw` | backend | Find the best insertion point for a new declaration | `position`<br>`target` | none | `SemanticInsertionResult` | single result |
| `raw/diagnostics` | `raw` | backend | Run Kotlin diagnostics on listed files | `filePaths` | `maxResults`<br>`pageToken` | `DiagnosticsResult` | single result |
| `raw/rename` | `raw` | backend | Plan a symbol rename by file position | `position`<br>`newName` | `dryRun` | `RenameResult` | single result |
| `raw/optimize-imports` | `raw` | backend | Optimize imports for one or more files | `filePaths` | none | `ImportOptimizeResult` | single result |
| `raw/apply-edits` | `raw` | backend | Apply a prepared edit plan with conflict detection | `edits`<br>`fileHashes` | `fileOperations` | `ApplyEditsResult` | single result |
| `raw/workspace-refresh` | `raw` | backend | Force a targeted or full workspace state refresh | none | `filePaths` | `RefreshResult` | single result |
| `raw/file-outline` | `raw` | backend | Get a hierarchical symbol outline for a file | `filePath` | none | `FileOutlineResult` | single result |
| `raw/workspace-symbol` | `raw` | backend | Search the workspace for symbols by name pattern | `pattern` | `kind`<br>`maxResults`<br>`regex`<br>`includeDeclarationScope` | `WorkspaceSymbolResult` | single result |
| `raw/workspace-search` | `raw` | backend | Search workspace file contents by text or regex | `pattern` | `regex`<br>`maxResults`<br>`fileGlob`<br>`caseSensitive` | `WorkspaceSearchResult` | single result |
| `raw/workspace-files` | `raw` | backend | List generation-bound workspace modules and Kotlin file pages | none | `kindDomain`<br>`moduleName`<br>`includeFiles`<br>`maxFilesPerModule`<br>`snapshotToken`<br>`pageToken` | `WorkspaceFilesResult` | single result |
| `raw/semantic-graph` | `raw` | backend | Project compiler-backed Kotlin symbols and relations | none | `filePaths`<br>`removedFilePaths`<br>`pageSize`<br>`continuation` | `SemanticGraphResult` | single result |
| `raw/workspace-files-continuation` | `raw` | backend | Issue or consume server-held public workspace-file continuation state | `action`<br>`ISSUE`: `identity`, `state`<br>`CONSUME`: `identity`, `pageToken` | none | `WorkspaceFilesContinuationResult` | `ISSUED`<br>`CONSUMED` |
| `raw/implementations` | `raw` | backend | Find concrete implementations and subclasses for a declaration | `position` | `maxResults` | `ImplementationsResult` | single result |
| `raw/code-actions` | `raw` | backend | Return available code actions at a file position | `position` | `diagnosticCode` | `CodeActionsResult` | single result |
| `raw/completions` | `raw` | backend | Return completion candidates available at a file position | `position` | `maxResults`<br>`kindFilter` | `CompletionsResult` | single result |
| `database/metrics` | `database` | sqlite | Query source-index metrics | `metric` | `workspaceRoot`<br>`limit`<br>`symbol`<br>`depth`<br>`offset`<br>`subject`<br>`fileGlob`<br>`folderFilter` | `RustMetricsResponse` | `METRICS_SUCCESS`<br>`METRICS_FAILURE` |

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
<summary><code>runtime/shutdown</code> - Ask the runtime host to shut down after this response is flushed</summary>

No request parameters.

Response type: `RuntimeLifecycleResponse`.

</details>

<details markdown="1">
<summary><code>runtime/restart</code> - Ask the runtime host to restart after this response is flushed</summary>

No request parameters.

Response type: `RuntimeLifecycleResponse`.

</details>

<details markdown="1">
<summary><code>capabilities</code> - Advertised read and mutation capabilities</summary>

No request parameters.

Response type: `BackendCapabilities`.

</details>

<details markdown="1">
<summary><code>mutation/submit</code> - Execute an idempotent semantic mutation and return its terminal result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `type` | `string` | yes | no | `RENAME`<br>`ADD_FILE`<br>`ADD_DECLARATION`<br>`ADD_IMPLEMENTATION`<br>`ADD_STATEMENT`<br>`REPLACE_DECLARATION` |

Request variants:

| Variant | Required params | Optional params |
| --- | --- | --- |
| `RENAME` | `idempotencyKey`<br>`request` | none |
| `ADD_FILE` | `idempotencyKey`<br>`request` | none |
| `ADD_DECLARATION` | `idempotencyKey`<br>`request` | none |
| `ADD_IMPLEMENTATION` | `idempotencyKey`<br>`request` | none |
| `ADD_STATEMENT` | `idempotencyKey`<br>`request` | none |
| `REPLACE_DECLARATION` | `idempotencyKey`<br>`request` | none |

Response type: `KastMutationExecutionResult`.

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

- Use this as the public source-index search surface before file reads or lower-level internal requests.
- Hard filters are enforced by source-index and compiler facts, never by semantic score.
- Nested filters include gradleProject, relativePathPrefix, productionOnly, excludePatterns, and usageFacets.
- usageFacets is the supported public filter for computed declaration facets; symbol/query does not expose clusterKinds.
- Token matching is computed from query text and indexed declaration fields.
- Graph depth defaults to 1 and is capped at 2 in the first implementation.
- Semantic discovery reports available=false when no semantic candidate provider is configured.

</details>

<details markdown="1">
<summary><code>symbol/resolve</code> - Resolve an exact simple or fully-qualified symbol identity with hard constraints and typed expected outcomes</summary>

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
Result variants: `RESOLVE_SUCCESS`, `RESOLVE_NOT_FOUND`, `RESOLVE_AMBIGUOUS`, `RESOLVE_FAILURE`.

Notes:

- The 'symbol' field accepts exact simple names or fully-qualified names; backticks are normalized only for comparison.
- kind, containingType, and fileHint are hard constraints rather than ranking hints.
- RESOLVE_NOT_FOUND and RESOLVE_AMBIGUOUS are expected typed outcomes and never select a fuzzy candidate.
- Existing internal consumers must match RESOLVE_NOT_FOUND and RESOLVE_AMBIGUOUS in addition to RESOLVE_SUCCESS and RESOLVE_FAILURE.
- Set includeDeclarationScope, includeDocumentation, surroundingLines, or includeSurroundingMembers only when the extra context is needed.

</details>

<details markdown="1">
<summary><code>selector/identity</code> - Authenticate an opaque selector handle for one operation family and recover its compact exact identity</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `selectorHandle` | `string` | yes | no |  |
| `family` | `string` | yes | no | `REFERENCES`<br>`CALLERS`<br>`CALLEES`<br>`IMPLEMENTATIONS`<br>`HIERARCHY`<br>`IMPACT`<br>`RENAME`<br>`REPLACE_DECLARATION` |

Response type: `KastSelectorIdentityResponse`.
Result variants: `AVAILABLE`, `SELECTOR_HANDLE_REJECTED`.

Notes:

- selectorHandle is opaque and must be carried unchanged from exact symbol resolution.
- The backend authenticates workspace, backend, semantic generation, and requested operation family without invoking symbol lookup.
- AVAILABLE returns only the compact authenticated identity needed by local composite commands; the CLI does not reconstruct selector flags.

</details>

<details markdown="1">
<summary><code>symbol/references</code> - Find every usage of a Kotlin symbol</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `selectorHandle` | `string` | no | no |  |
| `selector` | `object` | no | no |  |
| `includeDeclaration` | `boolean` | no | no |  |
| `includeUsageSiteScope` | `boolean` | no | no |  |
| `maxResults` | `integer` | no | no |  |
| `pageToken` | `string` | no | yes |  |

Response type: `KastReferencesResponse`.
Result variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`.

Notes:

- Provide exactly one of selector or selectorHandle. The explicit selector consumes the canonical FQ name, declaration file, and declaration start offset returned by exact symbol lookup.
- selectorHandle is an opaque ksh1 value returned by exact compiler-backed symbol resolution; carry it unchanged and do not reconstruct it.
- Optional kind and containingType values are hard identity assertions.
- maxResults bounds the returned page and the server-held INDEX or lazy IDEA continuation work.
- Pass PageInfo.nextPageToken as pageToken to consume the next deterministic, non-overlapping page. Tokens are opaque, one-use, and bound to the workspace, query options, evidence source, and source generation.
- Unknown, replayed, mismatched, evicted, or stale page tokens fail with a typed conflict.

</details>

<details markdown="1">
<summary><code>symbol/callers</code> - Page exact incoming or outgoing call relationships</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `selectorHandle` | `string` | no | no |  |
| `selector` | `object` | no | no |  |
| `direction` | `string` | yes | no | `incoming`<br>`outgoing` |
| `depth` | `integer` | no | no |  |
| `maxResults` | `integer` | no | no |  |
| `pageToken` | `string` | no | yes |  |

Response type: `KastCallersResponse`.
Result variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`.

Notes:

- Provide exactly one of selector or selectorHandle. The explicit selector consumes the canonical identity returned by exact symbol lookup.
- selectorHandle is an opaque ksh1 value returned by exact compiler-backed symbol resolution; carry it unchanged and do not reconstruct it.
- direction is fixed by the public callers or callees command.
- pageToken is an opaque backend-owned rth1 traversal handle.

</details>

<details markdown="1">
<summary><code>symbol/implementations</code> - Page exact implementation relationships</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `selectorHandle` | `string` | no | no |  |
| `selector` | `object` | no | no |  |
| `maxResults` | `integer` | no | no |  |
| `pageToken` | `string` | no | yes |  |

Response type: `KastImplementationsResponse`.
Result variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`.

</details>

<details markdown="1">
<summary><code>symbol/hierarchy</code> - Page exact type hierarchy relationships</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `selectorHandle` | `string` | no | no |  |
| `selector` | `object` | no | no |  |
| `direction` | `string` | yes | no | `SUPERTYPES`<br>`SUBTYPES`<br>`BOTH` |
| `depth` | `integer` | no | no |  |
| `maxResults` | `integer` | no | no |  |
| `pageToken` | `string` | no | yes |  |

Response type: `KastHierarchyResponse`.
Result variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`.

</details>

<details markdown="1">
<summary><code>symbol/rename</code> - Resolve or target a symbol and apply a rename</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `type` | `string` | yes | no | `RENAME_BY_SYMBOL_REQUEST`<br>`RENAME_BY_OFFSET_REQUEST` |

Request variants:

| Variant | Required params | Optional params |
| --- | --- | --- |
| `RENAME_BY_SYMBOL_REQUEST` | `symbol`<br>`newName` | `workspaceRoot`<br>`fileHint`<br>`kind`<br>`containingType` |
| `RENAME_BY_OFFSET_REQUEST` | `filePath`<br>`offset`<br>`newName` | `workspaceRoot` |

Response type: `KastRenameResponse`.
Result variants: `RENAME_SUCCESS`, `RENAME_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/write-and-validate</code> - Apply generated Kotlin code and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `type` | `string` | yes | no | `CREATE_FILE_REQUEST`<br>`INSERT_AT_OFFSET_REQUEST`<br>`REPLACE_RANGE_REQUEST` |

Request variants:

| Variant | Required params | Optional params |
| --- | --- | --- |
| `CREATE_FILE_REQUEST` | `filePath` | `workspaceRoot`<br>`content`<br>`contentFile` |
| `INSERT_AT_OFFSET_REQUEST` | `filePath`<br>`offset` | `workspaceRoot`<br>`content`<br>`contentFile` |
| `REPLACE_RANGE_REQUEST` | `filePath`<br>`startOffset`<br>`endOffset` | `workspaceRoot`<br>`content`<br>`contentFile` |

Response type: `KastWriteAndValidateResponse`.
Result variants: `WRITE_AND_VALIDATE_SUCCESS`, `WRITE_AND_VALIDATE_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/add-file</code> - Create a Kotlin file from a content file and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `filePath` | `string` | yes | no |  |
| `contentFile` | `string` | yes | no |  |

Response type: `KastScopeMutationResponse`.
Result variants: `SCOPE_MUTATION_SUCCESS`, `SCOPE_MUTATION_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/add-declaration</code> - Insert declaration content into a file or named Kotlin scope and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `placement` | `object` | yes | no |  |
| `contentFile` | `string` | yes | no |  |

Response type: `KastScopeMutationResponse`.
Result variants: `SCOPE_MUTATION_SUCCESS`, `SCOPE_MUTATION_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/add-implementation</code> - Insert implementation content into a file or named Kotlin scope and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `placement` | `object` | yes | no |  |
| `contentFile` | `string` | yes | no |  |

Response type: `KastScopeMutationResponse`.
Result variants: `SCOPE_MUTATION_SUCCESS`, `SCOPE_MUTATION_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/add-statement</code> - Insert statement content into a named executable Kotlin scope and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `insideScope` | `string` | yes | no |  |
| `anchor` | `string` | yes | no | `body-end` |
| `contentFile` | `string` | yes | no |  |

Response type: `KastScopeMutationResponse`.
Result variants: `SCOPE_MUTATION_SUCCESS`, `SCOPE_MUTATION_FAILURE`.

</details>

<details markdown="1">
<summary><code>symbol/replace-declaration</code> - Replace a named Kotlin declaration using declaration-scope evidence and validate the result</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `symbol` | `string` | yes | no |  |
| `contentFile` | `string` | yes | no |  |
| `fileHint` | `string` | no | yes |  |
| `kind` | `string` | no | yes | `class`<br>`interface`<br>`object`<br>`function`<br>`property` |
| `containingType` | `string` | no | yes |  |

Response type: `KastScopeMutationResponse`.
Result variants: `SCOPE_MUTATION_SUCCESS`, `SCOPE_MUTATION_FAILURE`.

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
| `maxResults` | `integer` | no | no |  |
| `pageToken` | `string` | no | yes |  |

Response type: `ReferencesResult`.

Notes:

- maxResults bounds the returned page and the server-held INDEX or lazy IDEA continuation work.
- pageToken is an opaque, one-use handle bound to the workspace, query options, evidence source, and source generation.
- Unknown, replayed, mismatched, evicted, or stale page tokens fail with a typed conflict.

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
| `maxResults` | `integer` | no | no |  |
| `pageToken` | `string` | no | yes |  |

Response type: `DiagnosticsResult`.

Notes:

- The first page computes exact severity counts and cardinality while capturing a server-held diagnostic snapshot.
- pageToken is an opaque, one-use handle bound to the ordered files, maxResults, and Kotlin PSI generation; continuations reuse the snapshot without refresh or recomputation.
- Unknown, replayed, mismatched, evicted, or stale page tokens fail with a typed conflict.

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
<summary><code>raw/workspace-files</code> - List generation-bound workspace modules and Kotlin file pages</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `kindDomain` | `string` | no | no | `SOURCE_ONLY`<br>`SCRIPT_ONLY`<br>`MIXED` |
| `moduleName` | `string` | no | yes |  |
| `includeFiles` | `boolean` | no | no |  |
| `maxFilesPerModule` | `integer` | no | yes |  |
| `snapshotToken` | `string` | no | yes |  |
| `pageToken` | `string` | no | yes |  |

Response type: `WorkspaceFilesResult`.

Notes:

- A metadata request captures a server-held generation-bound inventory and returns its opaque reusable snapshotToken.
- File pages echo kindDomain and snapshotToken, require one exact moduleName and a positive server-bounded maxFilesPerModule, and pass the preceding nextPageToken as pageToken.
- Each module page reports returnedFileCount equal to files.size; nextPageToken is non-null exactly when filesTruncated is true and another page remains.
- Unknown, replayed, mismatched, evicted, or stale snapshot and page handles fail instead of restarting enumeration.

</details>

<details markdown="1">
<summary><code>raw/semantic-graph</code> - Project compiler-backed Kotlin symbols and relations</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `filePaths` | `array of string` | no | no |  |
| `removedFilePaths` | `array of string` | no | no |  |
| `pageSize` | `integer` | no | no |  |
| `continuation` | `string` | no | yes |  |

Response type: `SemanticGraphResult`.

Notes:

- The scope must contain at least one selected or removed Kotlin path.
- Continuation is an opaque single-use token bound to the selected and removed path scope.
- Direct workspace targets outside the selected file scope are returned in boundarySymbols; every relation target is present in symbols or boundarySymbols.

</details>

<details markdown="1">
<summary><code>raw/workspace-files-continuation</code> - Issue or consume server-held public workspace-file continuation state</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `action` | `string` | yes | no | `ISSUE`<br>`CONSUME` |

Request variants:

| Variant | Required params | Optional params |
| --- | --- | --- |
| `ISSUE` | `identity`<br>`state` | none |
| `CONSUME` | `identity`<br>`pageToken` | none |

Response type: `WorkspaceFilesContinuationResult`.
Result variants: `ISSUED`, `CONSUMED`.

Notes:

- This internal method is not a backend capability or public agent command.
- ISSUE stores the supplied typed state and returns an opaque canonical random pageToken; CONSUME atomically claims a single-use handle and returns only a non-owning state projection.
- The exact workspace root, backend, normalized query, projection, and limit must match when consuming a handle.

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
<summary><code>database/metrics</code> - Query source-index metrics</summary>

| Field | Type | Required | Nullable | Values |
| --- | --- | --- | --- | --- |
| `workspaceRoot` | `string` | no | yes |  |
| `metric` | `string` | yes | no | `fanIn`<br>`fanOut`<br>`deadCode`<br>`impact`<br>`coupling`<br>`search`<br>`graph` |
| `limit` | `integer` | no | no |  |
| `symbol` | `string` | no | yes |  |
| `depth` | `integer` | no | no |  |
| `offset` | `integer` | no | no |  |
| `subject` | `object` | no | no |  |
| `fileGlob` | `string` | no | yes |  |
| `folderFilter` | `string` | no | yes |  |

Response type: `RustMetricsResponse`.
Result variants: `METRICS_SUCCESS`, `METRICS_FAILURE`.

Notes:

- Use this as the source-index database backing for kast agent impact and kast developer inspect metrics.
- Use fanIn, fanOut, deadCode, impact, coupling, search, or graph for supported metrics.

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

The generated YAML is checked into `cli-rs/protocol/openapi.yaml` for release
packaging and repository-local protocol inspection. It is not part of any
published docs site.

[:material-file-code: View openapi.yaml](openapi.yaml){ .md-button }

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
