---
type: CLI Operation
title: Verify an application
description: Prove an applied change against a resulting semantic generation.
resource: kast://operation/change.verify
tags:
  - kast
  - change
  - verify
  - receipt
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - change.verify
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
    symbols: [ChangeVerifyRequest, ChangeVerifyResult]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt
  - path: packaging/verify-published-runtime-delivery.sh
  - path: integration-tests/enterprise_acceptance.py
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Verify an application

Evaluate one applied change against a resulting semantic generation and its operation-specific obligations.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Verify this Kast application identity. Claim completion only when Kast returns a complete result with a receipt identity.

## Result

A complete result contains a receipt identity. This is the terminal proof that the observed result satisfied the plan.

Verification rejects a missing application, unavailable resulting generation, failed obligation, diagnostic regression, or rejected semantic delta. `Qualified` means proof remains incomplete.

## Recovery

When application or verification leaves a non-terminal journal state, use [Recover a plan](change-recover.md) with the original plan identity.
