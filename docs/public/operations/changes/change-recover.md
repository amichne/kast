---
type: CLI Operation
title: Recover a change plan
description: Recover one plan journal to a known prior, rolled-back, or manual-recovery state.
resource: kast://operation/change.recover
tags:
  - kast
  - change
  - recovery
  - journal
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - change.recover
kast_operation_role: primary
proof_level: contract
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
    symbols: [ChangeRecoverRequest, ChangeRecoverResult, ChangeRecoveryDocumentState]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Recover a change plan

Recover the durable journal for one plan after an interrupted or failed application.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Recover this Kast plan identity. Report the returned recovery state. Do not hide a manual-recovery requirement.

## Result

A complete result reports `prior-state`, `rolled-back`, or `recovery-required` as the known journal state.

`Qualified` means manual recovery is required. Rejection names a missing plan, unavailable journal, or failed recovery.

Recovery does not prove the intended change. A change is successful only when [Verify an application](change-verify.md) returns a verified receipt.
