---
type: Concept
title: Outcomes
description: The Complete, Qualified, and Rejected semantic result states.
resource: kast://concept/outcomes
tags:
  - kast
  - outcome
  - evidence
  - failure
timestamp: '2026-08-21T00:00:00Z'
kast_operations:
  - workspace.inspect
  - symbol.discover
  - symbol.resolve
  - symbol.describe
  - relation.read
  - traversal.run
  - diagnostic.check
  - change.plan
  - change.apply
  - change.verify
  - change.recover
kast_operation_role: related
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Outcomes

Every semantic operation returns one of three closed states. Transport success and semantic success are separate.

{{ kast_contract_links(page.meta, page.path) }}

## Complete

The operation met its contract and returned generation-bound evidence where the operation requires evidence.

## Qualified

The operation returned useful evidence and one or more explicit limitations. Qualified is not an approximate spelling of Complete.

Examples include result, work, time, depth, byte, and coverage limits.

## Rejected

The operation could not produce an admissible value. Each operation owns a finite rejection set.

## Agent rule

Parse the JSON outcome. Do not infer semantic success from process exit code alone. Preserve qualification and rejection reasons in the final answer.
