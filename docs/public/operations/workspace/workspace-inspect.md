---
type: CLI Operation
title: Inspect the workspace
description: Read the canonical root and current semantic workspace state.
resource: kast://operation/workspace.inspect
tags:
  - kast
  - workspace
  - inspection
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - workspace.inspect
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
    symbols: [WorkspaceInspectRequest, WorkspaceInspectResult, WorkspaceStateDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/workspace/WorkspaceCommands.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Inspect the workspace

Read the canonical repository root and the current semantic state used by later operations.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Use Kast to inspect this repository. Report the canonical root and the workspace state. Do not start manual recovery unless Kast returns a typed blocker.

## Result

A complete result contains the canonical root and one state: `Absent`, `Starting`, `Reconciling`, `Ready`, `Blocked`, or `Stopping`.

`Qualified` means reconciliation is in progress. `Rejected` means the root is unavailable or the runtime is blocked.

## Next operation

When the workspace is ready, [discover symbol candidates](../symbols/symbol-discover.md). Semantic commands demand the runtime automatically.
