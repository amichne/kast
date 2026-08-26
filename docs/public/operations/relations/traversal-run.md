---
type: CLI Operation
title: Traverse semantic relations
description: Compose one-hop relation reads under explicit depth and result bounds.
resource: kast://operation/traversal.run
tags:
  - kast
  - traversal
  - relation
  - bounded
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - traversal.run
kast_operation_role: primary
proof_level: enterprise
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
    symbols: [TraversalRunRequest, TraversalRunResult]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/traversal/TraversalCommands.kt
  - path: integration-tests/enterprise_acceptance.py
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Traverse semantic relations

Compose one-hop relation reads from one exact selector with explicit depth and result bounds.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Use Kast to traverse callees from this exact selector to depth 3 with at most 100 results. Preserve depth, result, and coverage limitations.

## Result

The result contains the exact symbols reached by the bounded traversal.

`Qualified` identifies a depth limit, result limit, or incomplete coverage. The operation never hides a bound hit as a complete graph.

## Use one hop when sufficient

Prefer [Read one semantic relation](relation-read.md) for direct questions. Traversal exists only for questions that require bounded composition.
