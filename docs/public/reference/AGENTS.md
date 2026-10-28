---
type: Engineering Guide
title: "Public reference engineering rules"
description: "Defines authored reference pages and generator ownership for callable OpenAPI contracts."
resource: "repo://docs/public/reference"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: cli/build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/MintlifyCallableSchemaProjection.kt
  - path: docs/public/docs.json
  - path: docs/public/reference/callables.openapi.json
---

# Public reference boundaries

- The MCP and tool RPC contract pages and transport model pages are authored content.
- `docs/okf-public.json` selects public views of shared OpenWiki OKF concepts.
  `docs/tooling/okf_public.py` owns those page shells and `snippets/okf/`.
  Change shared prose through OpenWiki, then regenerate the public projection.
  Keep routes and legacy anchors in the manifest. Run `verifyPublicKnowledge` to reject drift.
- `callables.openapi.json` is generated from the canonical CLI command graph and installed hosted
  schemas plus direct MCP and tool RPC result types. Never edit it directly.
- Update the callable artifact with `./gradlew :cli:generateMintlifyCallableReference` from the
  repository root.
- Run `./gradlew :cli:verifyMintlifyCallableReference` to reject projection drift, then run
  `mint validate` from `docs/public` to verify Mintlify can render the generated OpenAPI document.
