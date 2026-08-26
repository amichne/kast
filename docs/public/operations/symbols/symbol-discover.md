---
type: CLI Operation
title: Discover symbol candidates
description: Find bounded files, declarations, structures, or text matches without claiming exact identity.
resource: kast://operation/symbol.discover
tags:
  - kast
  - symbol
  - discovery
  - candidate
timestamp: '2026-08-21T00:00:00Z'
status: released
kast_operations:
  - symbol.discover
kast_operation_role: primary
proof_level: release
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
    symbols: [SymbolDiscoverRequest, SymbolDiscoveryDocument, SymbolDiscoverQualification]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/symbol/SymbolCommands.kt
  - path: integration-tests/enterprise_acceptance.py
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
---

# Discover symbol candidates

Find bounded candidates by name, source location, file structure, or text. Discovery does not establish exact symbol identity.

{{ kast_contract_links(page.meta, page.path) }}

## Ask your agent

> Use Kast to discover declarations named `HealthController`. Preserve every candidate selector. Do not infer the target from the display name alone.

## Modes

- `name` finds files, classes, or symbols with fuzzy or exact-name matching.
- `location` finds the declaration at a workspace-relative file and source offset.
- `structure` lists declarations in one file.
- `text` performs bounded text search in one file or the workspace.

## Result

Declaration items include a candidate selector. Pass that value unchanged to [Resolve exact identity](symbol-resolve.md).

A qualified result always names its limitations. Limits include result, byte, work, time, provider, dumb-mode, scope, and unsupported-item conditions. Do not interpret a qualified empty result as absence.
