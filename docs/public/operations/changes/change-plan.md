---
type: CLI Operation
title: Plan a typed change
description: Create one generation-bound change plan without writing source.
resource: kast://operation/change.plan
tags:
  - kast
  - change
  - plan
  - intent
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - change.plan
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
    symbols: [ChangePlanRequest, ChangePlanResult, ChangeIntentDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt
  - path: packaging/verify-published-runtime-delivery.sh
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Plan a typed change

Create one plan from one closed intent. Planning reads evidence and does not write the repository.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Use Kast to plan a rename of this exact selector to `NewName`. Show the plan outcome. Do not apply the plan until the plan is complete.

## Intents

- `add-file` adds one complete file.
- `add-declaration` adds one declaration to an exact target.
- `replace-declaration` replaces one exact declaration.
- `rename-symbol` renames one exact symbol.

## Result

A complete result contains a plan identity. Preserve it verbatim for [Apply a plan](change-apply.md).

Planning can reject when it requires exact symbol identity, edit authority, relation evidence, traversal evidence, diagnostics, or a ready workspace. The rejection names the missing proof instead of silently obtaining stronger authority.
