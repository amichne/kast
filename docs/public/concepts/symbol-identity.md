---
type: Concept
title: Symbol identity
description: The monotonic refinement from a broad query to one exact generation-bound selector.
resource: kast://concept/symbol-identity
tags:
  - kast
  - symbol
  - identity
  - selector
timestamp: '2026-08-21T00:00:00Z'
kast_operations:
  - symbol.discover
  - symbol.resolve
  - symbol.describe
  - relation.read
  - traversal.run
  - change.plan
kast_operation_role: related
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
    symbols: [SymbolDiscoverTargetDocument, SymbolResolveRequest, SymbolDescribeRequest]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Symbol identity

Kast refines identity instead of asking the caller to reconstruct it.

{{ kast_contract_links(page.meta, page.path) }}

```text
query
  → candidate
  → candidate selector
  → exact selector
```

## Query

A query describes what to discover. It can be a name, source location, file structure request, or bounded text search.

## Candidate

A candidate is one bounded discovery result. A declaration candidate carries an opaque candidate selector.

## Exact selector

Resolution refines a candidate selector into one exact selector bound to the current semantic generation. Exact operations consume that selector.

Do not substitute a qualified name, file and offset, display name, or copied JSON fields for the selector. After the generation changes, use discovery and resolution again.
