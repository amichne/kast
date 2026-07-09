---
title: Automate With Agents
description: Use typed Kast commands from agents, scripts, and repository guidance.
icon: lucide/bot
---

# Automate With Agents

Use typed `kast agent` commands when an agent or script needs compiler-backed
Kotlin evidence. Keep automation on the public command dialect instead of raw
transport, generated catalog lookup, byte offsets, or implementation class
names.

## Prepare Repository Guidance

On macOS, the IntelliJ plugin prepares repository guidance when the workspace
opens. On non-macOS headless or server hosts, run setup once per repository.

```console
kast setup --dry-run --workspace-root "$PWD"
kast setup --workspace-root "$PWD"
```

Setup installs only:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected context file

Use `--context-file` when the repository needs an explicit `AGENTS.md`,
`CODEX.md`, `CLAUDE.md`, or `AGENTS.local.md` target.

```console
kast setup --context-file "$PWD/cli-rs/AGENTS.md" --force
```

Setup does not install Copilot package files, portable Markdown instruction
packages, session hooks, generated catalog copies, or workflow helper assets.

## Use Structured Output Deliberately

Human operator commands default to readable output in interactive terminals and
accept `--output json`. Captured or agent-run commands may default to compact
TOON. Pass the output mode explicitly when automation needs a stable parser
contract.

```console
kast --output json ready --for agent --workspace-root "$PWD"
kast --output json developer runtime status --workspace-root "$PWD"
kast agent symbol --query OrderService --output json --workspace-root "$PWD"
```

## Verify Before Depending On Answers

Run `agent verify` before relying on semantic answers in a fresh, moved, or
recently repaired workspace.

```console
kast agent verify --workspace-root "$PWD"
```

The command reports backend health, runtime state, capabilities, and the active
workspace root. If verification fails, diagnose the runtime before retrying
symbol, diagnostics, impact, or mutation commands.

## Prefer Typed Agent Commands

The public V1 semantic surface is:

- `kast agent verify`
- `kast agent symbol`
- `kast agent diagnostics`
- `kast agent impact`
- `kast agent rename`
- `kast agent add-file`
- `kast agent add-declaration`
- `kast agent add-implementation`
- `kast agent add-statement`
- `kast agent replace-declaration`
- `kast agent lsp --stdio`

Use [agent command reference](../reference/agent-commands.md) for command
lookup and [plan safe edits](plan-safe-edits.md) for mutation workflows.
