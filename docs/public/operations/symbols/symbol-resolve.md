---
type: CLI Operation
title: Resolve exact symbol identity
description: Refine one candidate selector into one generation-bound exact selector.
resource: kast://operation/symbol.resolve
tags:
  - kast
  - symbol
  - identity
  - selector
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - symbol.resolve
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
    symbols: [SymbolResolveRequest, SymbolResolveResult]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/symbol/SymbolCommands.kt
  - path: integration-tests/enterprise_acceptance.py
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Resolve exact symbol identity

Refine one discovery candidate into one exact, generation-bound selector.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Resolve this candidate selector with Kast. Preserve the returned exact selector verbatim. Stop if the candidate is stale, ambiguous, or missing.

## Result

A complete result contains one exact selector. That selector is the identity consumed by exact reads and symbol-targeted changes.

Resolution rejects stale candidates, ambiguity, and missing declarations. It does not guess among overloads or same-named declarations.

## Next operation

Use the exact selector to [describe the symbol](symbol-describe.md), [read a relation](../relations/relation-read.md), or [plan a change](../changes/change-plan.md).
