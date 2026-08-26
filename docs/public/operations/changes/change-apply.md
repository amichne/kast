---
type: CLI Operation
title: Apply a change plan
description: Execute one admitted plan and return an unverified application identity.
resource: kast://operation/change.apply
tags:
  - kast
  - change
  - apply
  - application
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - change.apply
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
    symbols: [ChangeApplyRequest, ChangeApplyResult]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt
  - path: packaging/verify-published-runtime-delivery.sh
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Apply a change plan

Apply one previously issued plan after revalidating its root, generation, content, and write scope.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Apply this Kast plan identity. Preserve the returned application identity. Do not call the change verified or complete until `change.verify` returns a receipt identity.

## Result

A complete apply result contains an application identity. It means the admitted physical application occurred. It does not mean the semantic result is verified.

Application can reject a missing plan, root mismatch, stale generation, changed content, or rejected write scope. Rollback and recovery requirements remain explicit outcomes.

## Required next step

Pass the application identity to [Verify an application](change-verify.md).
