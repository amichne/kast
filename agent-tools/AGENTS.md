---
type: Engineering Guide
title: "Kast agent onboarding"
description: "Defines the shared brief, generated references, and passive host adapters."
resource: "repo://agent-tools"
tags: [kast, agents, plugins]
timestamp: "2026-10-19T14:21:11Z"
source_revision: 02adf9c3dda16bcaa86f3e33557ba3763f73a6db
code_sources:
  - path: agent-tools/onboarding.template.md
  - path: agent-tools/README.md
  - path: agent-tools/hooks/session_start.py
  - path: packaging/generate-public-query.py
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - path: pi/mcp-extension.ts
  - path: cli/src/test/js/native-onboarding.test.mjs
---

# Agent onboarding

`onboarding.template.md` owns the shared prose.
`packaging/generate-public-query.py` owns `references/onboarding.md`, `references/query-schema.json`, and `references/query-examples.json`.
Change the template or the authored public schema before regeneration.
Do not add a discoverable `SKILL.md` or a standalone skill archive.

`README.md` owns the on-demand Pi inventory example.
Its executable assertions stay in `cli/src/test/js/native-onboarding.test.mjs`.

`hooks/session_start.py` owns context assembly and host envelopes.
Keep its runtime passive, bounded, offline, and free of semantic calls or workspace writes.
Keep its PEP 723 metadata and lock together.
The context hooks use Python 3.9 or later with the standard library.
Keep dependency downloads and workspace preparation outside these hooks.
The MCP session owns IntelliJ preparation through `KastMcpServer` and `McpWorkspaceOperationClient`.
The Codex and Copilot manifests select their own hook configuration and share the same MCP entry.
The Pi adapter in `pi/mcp-extension.ts` reads bundled resources directly with Node and uses native MCP support.

Run `python3 packaging/test-agent-onboarding.py` for hook output, rejection, and host-command cases.
Run `python3 packaging/test-public-query-generation.py` for schema and brief parity.
Run `node --test cli/src/test/js/native-onboarding.test.mjs` for Pi adapter cases.
These tests do not prove model behavior or live host delivery.
