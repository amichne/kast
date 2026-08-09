---
type: Generated Reference
title: Semantic Operation Contract
description: Generated request and response facts for Kast's semantic operations.
tags: [generated, reference, semantic-evidence]
code_sources:
  - path: cli-rs/protocol/source/commands.json
---

> Generated file. Do not edit this page directly.

# Semantic Operation Contract

This page records mechanically knowable operation facts. It is generated from
`cli-rs/protocol/source/commands.json`; change that typed catalog, then regenerate
this page.

The reference describes the semantic contract behind Kast. It is not a guide for
operating an agent or a substitute for the release-matched CLI help.

## Symbol evidence

### `symbol/scaffold`

Gather structural generation context for a Kotlin file.

- Data source: `backend`
- Response type: `KastScaffoldResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `targetFile` | required | `string` | — |
| `targetSymbol` | optional | `string` | — |
| `mode` | optional | `string` | `implement`, `replace`, `consolidate`, `extract` |
| `kind` | optional | `string` | `class`, `interface`, `object`, `function`, `property` |

### `symbol/discover`

Rank candidate declarations for a simple symbol name.

- Data source: `backend`
- Response type: `KastDiscoverResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `symbol` | required | `string` | — |
| `fileHint` | optional | `string` | — |
| `line` | optional | `integer` | — |
| `codeSnippet` | optional | `string` | — |
| `kind` | optional | `string` | `class`, `interface`, `object`, `function`, `property` |
| `containingType` | optional | `string` | — |
| `maxResults` | optional | `integer` | — |
| `includeDeclarationScope` | optional | `boolean` | — |

#### Contract guarantees

- Use this before symbol/resolve when a simple name is ambiguous or context is available.
- Candidates include resolveParams and nextRequest fields that can be sent to symbol/resolve.

### `symbol/query`

Query compiler-indexed declarations with symbolic hard filters, fielded lexical/name matching, bounded graph relationship evidence, and optional semantic discovery evidence.

- Data source: `sqlite`
- Response type: `KastSymbolQueryResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `query` | required | `string` | — |
| `modes` | optional | `array` | — |
| `filters` | optional | `object` | — |
| `anchor` | optional | `object` | — |
| `graph` | optional | `object` | — |
| `semantic` | optional | `object` | — |
| `limit` | optional | `integer` | — |
| `includeEvidence` | optional | `boolean` | — |
| `includeNextRequests` | optional | `boolean` | — |

#### Contract guarantees

- Use this as the public source-index search surface before file reads or lower-level internal requests.
- Hard filters are enforced by source-index and compiler facts, never by semantic score.
- Nested filters include gradleProject, relativePathPrefix, productionOnly, excludePatterns, and usageFacets.
- usageFacets is the supported public filter for computed declaration facets; symbol/query does not expose clusterKinds.
- Token matching is computed from query text and indexed declaration fields.
- Graph depth defaults to 1 and is capped at 2 in the first implementation.
- Semantic discovery reports available=false when no semantic candidate provider is configured.

### `symbol/resolve`

Resolve an exact simple or fully-qualified symbol identity with hard constraints and typed expected outcomes.

- Data source: `backend`
- Response type: `KastResolveResponse`
- Response variants: `RESOLVE_SUCCESS`, `RESOLVE_NOT_FOUND`, `RESOLVE_AMBIGUOUS`, `RESOLVE_FAILURE`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `symbol` | required | `string` | — |
| `fileHint` | optional | `string` | — |
| `kind` | optional | `string` | `class`, `interface`, `object`, `function`, `property` |
| `containingType` | optional | `string` | — |
| `includeDeclarationScope` | optional | `boolean` | — |
| `includeDocumentation` | optional | `boolean` | — |
| `surroundingLines` | optional | `integer` | — |
| `includeSurroundingMembers` | optional | `boolean` | — |

#### Contract guarantees

- The 'symbol' field accepts exact simple names or fully-qualified names; backticks are normalized only for comparison.
- kind, containingType, and fileHint are hard constraints rather than ranking hints.
- RESOLVE_NOT_FOUND and RESOLVE_AMBIGUOUS are expected typed outcomes and never select a fuzzy candidate.
- Existing internal consumers must match RESOLVE_NOT_FOUND and RESOLVE_AMBIGUOUS in addition to RESOLVE_SUCCESS and RESOLVE_FAILURE.
- Set includeDeclarationScope, includeDocumentation, surroundingLines, or includeSurroundingMembers only when the extra context is needed.

### `selector/identity`

Authenticate an opaque selector handle for one operation family and recover its compact exact identity.

- Data source: `backend`
- Response type: `KastSelectorIdentityResponse`
- Response variants: `AVAILABLE`, `SELECTOR_HANDLE_REJECTED`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `selectorHandle` | required | `string` | — |
| `family` | required | `string` | `REFERENCES`, `CALLERS`, `CALLEES`, `IMPLEMENTATIONS`, `HIERARCHY`, `IMPACT`, `RENAME`, `REPLACE_DECLARATION`, `IDENTITY` |

#### Contract guarantees

- selectorHandle is opaque and must be carried unchanged from exact symbol resolution.
- The backend authenticates workspace, backend, semantic generation, and requested operation family without invoking symbol lookup.
- AVAILABLE returns only the compact authenticated identity needed by local composite commands; the CLI does not reconstruct selector flags.

### `symbol/references`

Find every usage of a Kotlin symbol.

- Data source: `backend`
- Response type: `KastReferencesResponse`
- Response variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `selectorHandle` | optional | `string` | — |
| `selector` | optional | `object` | — |
| `includeDeclaration` | optional | `boolean` | — |
| `includeUsageSiteScope` | optional | `boolean` | — |
| `maxResults` | optional | `integer` | — |
| `pageToken` | optional | `string` | — |

Exactly one of these fields is required: `selectorHandle`, `selector`.

#### Contract guarantees

- Provide exactly one of selector or selectorHandle. The explicit selector consumes the canonical FQ name, declaration file, and declaration start offset returned by exact symbol lookup.
- selectorHandle is an opaque ksh1 value returned by exact compiler-backed symbol resolution; carry it unchanged and do not reconstruct it.
- Optional kind and containingType values are hard identity assertions.
- maxResults bounds the returned page and the server-held INDEX or lazy IDEA continuation work.
- Pass PageInfo.nextPageToken as pageToken to consume the next deterministic, non-overlapping page. Tokens are opaque, one-use, and bound to the workspace, query options, evidence source, and source generation.
- Unknown, replayed, mismatched, evicted, or stale page tokens fail with a typed conflict.

### `symbol/callers`

Page exact incoming or outgoing call relationships.

- Data source: `backend`
- Response type: `KastCallersResponse`
- Response variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `selectorHandle` | optional | `string` | — |
| `selector` | optional | `object` | — |
| `direction` | required | `string` | `incoming`, `outgoing` |
| `depth` | optional | `integer` | — |
| `maxResults` | optional | `integer` | — |
| `pageToken` | optional | `string` | — |

Exactly one of these fields is required: `selectorHandle`, `selector`.

#### Contract guarantees

- Provide exactly one of selector or selectorHandle. The explicit selector consumes the canonical identity returned by exact symbol lookup.
- selectorHandle is an opaque ksh1 value returned by exact compiler-backed symbol resolution; carry it unchanged and do not reconstruct it.
- direction is fixed by the public callers or callees command.
- pageToken is an opaque backend-owned rth1 traversal handle.

### `symbol/implementations`

Page exact implementation relationships.

- Data source: `backend`
- Response type: `KastImplementationsResponse`
- Response variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `selectorHandle` | optional | `string` | — |
| `selector` | optional | `object` | — |
| `maxResults` | optional | `integer` | — |
| `pageToken` | optional | `string` | — |

Exactly one of these fields is required: `selectorHandle`, `selector`.

### `symbol/hierarchy`

Page exact type hierarchy relationships.

- Data source: `backend`
- Response type: `KastHierarchyResponse`
- Response variants: `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`, `UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `CURSOR_STALE`, `CURSOR_INVALID`, `SELECTOR_HANDLE_REJECTED`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `selectorHandle` | optional | `string` | — |
| `selector` | optional | `object` | — |
| `direction` | required | `string` | `SUPERTYPES`, `SUBTYPES`, `BOTH` |
| `depth` | optional | `integer` | — |
| `maxResults` | optional | `integer` | — |
| `pageToken` | optional | `string` | — |

Exactly one of these fields is required: `selectorHandle`, `selector`.

### `symbol/rename`

Resolve or target a symbol and apply a rename.

- Data source: `backend`
- Response type: `KastRenameResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `type` | required | `string` | `RENAME_BY_SYMBOL_REQUEST`, `RENAME_BY_OFFSET_REQUEST` |

### `symbol/write-and-validate`

Apply generated Kotlin code and validate the result.

- Data source: `backend`
- Response type: `KastWriteAndValidateResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `type` | required | `string` | `CREATE_FILE_REQUEST`, `INSERT_AT_OFFSET_REQUEST`, `REPLACE_RANGE_REQUEST` |

### `symbol/add-file`

Create a Kotlin file from a content file and validate the result.

- Data source: `backend`
- Response type: `KastScopeMutationResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `filePath` | required | `string` | — |
| `contentFile` | required | `string` | — |

### `symbol/add-declaration`

Insert declaration content into a file or named Kotlin scope and validate the result.

- Data source: `backend`
- Response type: `KastScopeMutationResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `placement` | required | `object` | — |
| `contentFile` | required | `string` | — |

### `symbol/add-implementation`

Insert implementation content into a file or named Kotlin scope and validate the result.

- Data source: `backend`
- Response type: `KastScopeMutationResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `placement` | required | `object` | — |
| `contentFile` | required | `string` | — |

### `symbol/add-statement`

Insert statement content into a named executable Kotlin scope and validate the result.

- Data source: `backend`
- Response type: `KastScopeMutationResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `insideScope` | required | `string` | — |
| `anchor` | required | `string` | `body-end` |
| `contentFile` | required | `string` | — |

### `symbol/replace-declaration`

Replace a named Kotlin declaration using declaration-scope evidence and validate the result.

- Data source: `backend`
- Response type: `KastScopeMutationResponse`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `symbol` | required | `string` | — |
| `contentFile` | required | `string` | — |
| `fileHint` | optional | `string` | — |
| `kind` | optional | `string` | `class`, `interface`, `object`, `function`, `property` |
| `containingType` | optional | `string` | — |

## Graph coverage

### `graph/coverage`

Report generation-pinned Kotlin graph coverage by file, module, and compilation.

- Data source: `sqlite`
- Response type: `KastGraphCoverageResult`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `scope` | optional | `object` | — |
| `continuation` | optional | `string` | — |
| `limit` | optional | `integer` | — |

#### Contract guarantees

- Every compilation-owned Kotlin file is classified as indexed, excluded, failed, or stale.
- Complete negative answers require complete eligible coverage at the returned generation.
- A truncated result returns an authenticated continuation; a terminal result returns continuation=null.

## Repository relationships

### `repository/query`

Answer one bounded repository question with visible scope, coverage, and evidence.

- Data source: `sqlite`
- Response type: `KastRepositoryQueryResult`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `workspaceRoot` | optional | `string` | — |
| `canonicalKey` | optional | `string` | — |
| `labelIndex` | optional | `string` | — |
| `question` | required | `string` | — |
| `querySyntax` | optional | `string` | `natural_language`, `regex` |
| `intent` | required | `string` | `resolve`, `path`, `incoming_impact`, `outgoing_impact`, `architecture`, `context_relationship` |
| `scope` | optional | `object` | — |
| `evidenceContinuation` | optional | `string` | — |
| `continuation` | optional | `string` | — |
| `limits` | required | `object` | — |

#### Contract guarantees

- Approximate discovery terminates in exact canonical identities or bounded ambiguity.
- Precomputed labels may retrieve candidates but cannot supply identity, location, relationships, evidence, completeness, cardinality, or certainty.
- Every response exposes generation, scope, coverage, filters, bounds, ordering, truncation, and continuation.

## Semantic changes

### `mutation/submit`

Execute an idempotent semantic mutation and return its terminal result.

- Data source: `backend`
- Response type: `KastMutationExecutionResult`

#### Request fields

| Field | Requirement | Type | Closed values |
| --- | --- | --- | --- |
| `type` | required | `string` | `RENAME`, `ADD_FILE`, `ADD_DECLARATION`, `ADD_IMPLEMENTATION`, `ADD_STATEMENT`, `REPLACE_DECLARATION` |
