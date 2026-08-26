---
type: Runtime Flow
title: Runtime
description: The control product, content-addressed semantic runtime, and exact-root indexer lifecycle.
resource: kast://concept/runtime
tags:
  - kast
  - runtime
  - indexer
  - lifecycle
timestamp: '2026-08-21T00:00:00Z'
kast_operations:
  - workspace.inspect
kast_operation_role: related
kast_lifecycle_commands:
  - start
  - stop
  - status
  - clean
  - reindex
code_sources:
  - path: install.sh
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/RuntimeBoundary.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt
---

# Runtime

Kast separates the small installed control product from the content-addressed semantic runtime.

{{ kast_contract_links(page.meta, page.path) }}

## Normal path

A semantic command discovers the canonical root, realizes the exact runtime when required, starts or reuses the exact-root indexer, and performs the typed operation.

The user does not need to run `kast start` during normal onboarding.

## Lifecycle commands

Lifecycle commands exist for observation and explicit recovery. They are not semantic operations and do not appear in the canonical eleven-operation registry.

Use `status` before destructive recovery. Use `reindex` only when the typed blocker or troubleshooting evidence requires rebuilding semantic state.
