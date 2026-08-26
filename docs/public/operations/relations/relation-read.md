---
type: CLI Operation
title: Read one semantic relation
description: Read one bounded compiler-grounded relation hop from an exact symbol.
resource: kast://operation/relation.read
tags:
  - kast
  - relation
  - references
  - callers
  - callees
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - relation.read
kast_operation_role: primary
proof_level: enterprise
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
    symbols: [RelationReadRequest, RelationReadResult, RelationKindDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/relation/RelationCommands.kt
  - path: integration-tests/enterprise_acceptance.py
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Read one semantic relation

Read one direct semantic hop from an exact selector.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Use Kast to read the direct callers of this exact selector. Report the returned targets and whether coverage is complete or qualified.

## Relation kinds

`references`, `callers`, `callees`, `implementations`, `inheritors`, `overrides`, and `type-uses` are closed meanings. Each invocation selects one meaning.

## Result

A complete result contains bounded exact target symbols. `Qualified` names a result limit or incomplete coverage.

Under incomplete coverage, no returned target means “not observed in the admitted coverage.” It does not mean “does not exist.”

## Next operation

Use [Traverse relations](traversal-run.md) only when the question requires more than one hop.
