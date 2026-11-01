---
type: Engineering Guide
title: "Public reference engineering rules"
description: "Defines authored reference pages and the generators for callable contracts and shared OKF views."
resource: "repo://docs/public/reference"
tags: [kast, navigation]
timestamp: "2026-10-31T12:39:38Z"
source_revision: 647fffc46f6247fac5b0fc632ae019d157bf9020
code_sources:
  - path: cli/build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/MintlifyCallableSchemaProjection.kt
  - path: docs/public/docs.json
  - path: docs/public/reference/callables.openapi.json
  - path: docs/okf-public.json
  - path: docs/tooling/okf_public.py
---

# Public reference ownership

- The MCP and tool RPC contract pages and transport model pages are authored content.
- `cli.mdx` owns common native CLI examples. The installed `kast describe` output owns the current command contract.
- `installation.mdx` and `management.mdx` describe managed build selection and activation. `integrations.mdx` owns CLI guidance and explicit optional MCP setup.
- `docs/okf-public.json` selects public views of shared OpenWiki OKF concepts.
  `docs/tooling/okf_public.py` owns those page shells and `snippets/okf/`.
  Change shared prose through OpenWiki, then regenerate the public projection.
  Keep routes and legacy anchors in the manifest. Run `verifyPublicKnowledge` to reject drift.
- The manifest's optional `introduction` owns reader orientation before shared content.
  Use it for current public syntax and next-page links. Keep canonical explanations and their qualifications in OpenWiki.
- `callables.openapi.json` is generated from the canonical CLI command graph and installed hosted
  schemas plus direct MCP and tool RPC result types. Never edit it directly.
- Update the callable artifact with `./gradlew :cli:generateMintlifyCallableReference` from the
  repository root.
- Run `./gradlew :cli:verifyMintlifyCallableReference` to reject projection drift, then run
  `mint validate` from `docs/public` to verify Mintlify can render the generated OpenAPI document.
