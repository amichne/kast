---
type: Engineering Guide
title: "Kast agent onboarding"
description: "Defines the bundled skill, task service, shared brief, and host adapters."
resource: "repo://agent-tools"
tags: [kast, agents, plugins]
timestamp: "2026-10-29T23:41:55Z"
source_revision: 90ef99b6c9edfd6c75d687865985546be29cdb16
code_sources:
  - path: agent-tools/skills/kast/SKILL.md
  - path: agent-tools/skills/kast/public-cli.md
  - path: agent-tools/skills/kast/scripts/tasks.py
  - path: agent-tools/skills/kast/scripts/service.py
  - path: copilot/task-extension.mjs
  - path: agent-tools/onboarding.template.md
  - path: agent-tools/README.md
  - path: agent-tools/hooks/session_start.py
  - path: packaging/generate-public-query.py
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - path: pi/mcp-extension.ts
  - path: cli/src/test/js/native-onboarding.test.mjs
---

# Agent integration

`onboarding.template.md` owns the shared prose.
`packaging/generate-public-query.py` owns `references/onboarding.md`, `references/query-schema.json`, and `references/query-examples.json`.
The same generator owns `references/query-topics.md` and its named topic files.
It also owns `references/query-common-schema.json` from `catalogs.COMMON` in the canonical schema.
Change the template or the authored public schema before regeneration.
Ship `skills/kast/SKILL.md` inside the plugin. Keep its scripts and dependency locks together.
Do not publish a separate skill archive.

`skills/kast/public-cli.md` owns authored guidance for public shell reads.
The bundled `skills/kast/SKILL.md` links to it.
Edit that guide directly. Keep query references under their existing generator owner.

`README.md` owns the on-demand Pi inventory example.
Its executable assertions stay in `cli/src/test/js/native-onboarding.test.mjs`.

`hooks/session_start.py` owns context assembly and host envelopes.
Keep its runtime passive, bounded, offline, and free of semantic calls or workspace writes.
Keep its PEP 723 metadata and lock together.
The context hooks use Python 3.9 or later with the standard library.
Keep dependency downloads and workspace preparation outside these hooks.
The task service delegates preparation and semantic work to the installed tool RPC.
`skills/kast/scripts/tasks.py` owns the catalog shared by MCP and the Copilot SDK adapter.
Keep binary and wire contracts unchanged. Expose advanced operations through `schema` and `raw`.
The Codex and Copilot manifests select their own hook configuration and share the same MCP entry.
The Pi adapter in `pi/mcp-extension.ts` reads bundled resources directly with Node and uses native MCP support.

Run `python3 packaging/test-agent-onboarding.py` for hook output, rejection, and host-command cases.
Run `python3 packaging/test-public-query-generation.py` for schema and brief parity.
Run `node --test cli/src/test/js/native-onboarding.test.mjs` for Pi adapter cases.
These tests do not prove model behavior or live host delivery.

Run `uv run --locked pytest` from `packaging/task-access` for task admission, real MCP sessions, and child cancellation.
