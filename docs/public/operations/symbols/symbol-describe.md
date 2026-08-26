---
type: CLI Operation
title: Describe an exact symbol
description: Read structured evidence for one exact generation-bound selector.
resource: kast://operation/symbol.describe
tags:
  - kast
  - symbol
  - description
  - selector
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - symbol.describe
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
    symbols: [SymbolDescribeRequest, SymbolDescribeResult, SymbolDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/symbol/SymbolCommands.kt
  - path: packaging/verify-published-runtime-delivery.sh
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Describe an exact symbol

Read the kind, name, qualified identity when available, file, range, and exact selector for one symbol.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Describe this exact selector with Kast. Use the structured symbol evidence as the identity source. Do not reconstruct identity from its file or qualified name.

## Result

A complete result returns one structured symbol. The returned selector must match the requested selector.

The operation rejects a stale selector or a missing declaration. A selector becomes stale when the published semantic generation changes.

## Related operations

Use [Read one relation](../relations/relation-read.md) for direct callers, callees, references, implementations, inheritance, overrides, or type uses.
