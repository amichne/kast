---
type: CLI Operation
title: Check compiler diagnostics
description: Read bounded compiler diagnostics for one declared scope.
resource: kast://operation/diagnostic.check
tags:
  - kast
  - diagnostic
  - compiler
  - coverage
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - diagnostic.check
kast_operation_role: primary
proof_level: contract
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
    symbols: [DiagnosticCheckRequest, DiagnosticCheckResult]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/diagnostic/DiagnosticCommands.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Check compiler diagnostics

Read bounded compiler diagnostics for one declared scope and one semantic generation.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Use Kast to check diagnostics for this scope. Report every diagnostic and state whether coverage is complete or qualified.

## Result

A complete result contains all admitted diagnostics within the declared contract. `Qualified` names a result limit or incomplete coverage.

An empty qualified result does not prove that the scope has no diagnostics. A rejected scope does not become a broader search.

## Change verification

Diagnostics can contribute to [Verify an application](../changes/change-verify.md), but diagnostics alone do not establish change success.
