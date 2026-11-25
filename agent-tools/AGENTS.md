---
type: Engineering Guide
title: "Kast agent onboarding"
description: "Defines the bundled skill, shared brief, session hook, and native MCP composition."
resource: "repo://agent-tools"
tags: [kast, agents, plugins]
timestamp: "2026-11-21T00:00:00Z"
source_revision: 75f3c85bbe6faf8a953cf4717461c4eebec5e452
code_sources:
  - path: agent-tools/mcp.json
  - path: agent-tools/skills/kast/SKILL.md
  - path: agent-tools/skills/kast/public-cli.md
  - path: agent-tools/onboarding.template.md
  - path: agent-tools/README.md
  - path: agent-tools/hooks/hooks.json
  - path: agent-tools/hooks.json
  - path: agent-tools/package.json
  - path: agent-tools/pi/session-start.ts
  - path: agent-tools/hooks/session_start.py
  - path: packaging/internal/query/generate-public-query.py
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
---

# Agent integration

`onboarding.template.md` owns the shared prose.
`packaging/internal/query/generate-public-query.py` owns `references/onboarding.md`, `references/query-schema.json`, and `references/query-examples.json`.
The same generator owns `references/query-topics.md` and its named topic files.
It also owns `references/query-common-schema.json` from `catalogs.COMMON` in the canonical schema.
Change the template or the authored public schema before regeneration.
Ship `skills/kast/SKILL.md` inside both compositions.
The default skill links only to resources within its own directory.
Do not publish a separate skill archive.

`skills/kast/public-cli.md` owns authored guidance for public shell reads.
The bundled `skills/kast/SKILL.md` links to it.
Edit that guide directly. Keep query references under their existing generator owner.

`hooks/session_start.py` owns context assembly and the Codex envelope.
Keep its runtime passive, bounded, offline, and free of semantic calls or workspace writes.
Keep its PEP 723 metadata and lock together.
The legacy context helper uses Python 3.9 or later with the standard library. Generated plugins exclude this helper.
`hooks/hooks.json` calls `kast --internal-entrypoint=query hook session-start` for passive context.
The native query executable owns event admission and project selection. Hooks do not grant trust, create workspace state, or start a Host.
Keep workspace decisions and IDE effects outside the harness adapters.
`--host agent` selects the one session-start envelope that Claude Code and Codex read. Outside a Gradle project its reply is empty.
`--host plain` prints only `additionalContext`, for GitHub Copilot and Pi. The same empty reply applies.
`hooks.json` owns the Copilot hook. Copilot prefers it to `hooks/hooks.json`.
`package.json` owns the Pi package, and `pi/session-start.ts` runs the same passive command. Pi has no hook configuration.
The default Codex manifest selects the canonical CLI skill and session hook.
The generator derives the Claude Code manifest and marketplace from that manifest. Do not author a second identity.
Claude Code finds `skills/`, `hooks/hooks.json`, and `.mcp.json` at their default paths.
`mcp.json` owns the optional MCP launcher input. It runs the native MCP server through `kast --internal-entrypoint=mcp`.
The launcher resolves `kast` from `${HOME}/.local/bin` first, as the session hook does.
The generator places optional MCP resources under `compositions/mcp`.
Only that composition receives a root `.mcp.json`.
No generated composition selects Python context hooks or a Python MCP server.
Each agent owns its plugin configuration and cached resources.

Run `mise exec -- uv run --locked --project packaging pytest packaging/tests/test-agent-onboarding.py` for hook output, rejection, host-command, and MCP launcher cases.
Run `mise exec -- uv run --locked --project packaging pytest packaging/tests/test-public-query-generation.py` for schema and brief parity.
These tests do not prove model behavior or live host delivery.
