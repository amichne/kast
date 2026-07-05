---
title: Agent Commands
description: Use typed `kast agent` commands for compiler-backed Kotlin work.
icon: lucide/bot
---

# Agent Commands

`kast agent` is the typed, machine-oriented surface for agents and scripts. It
defaults to compact TOON; pass `--output json` when a script needs JSON.

## Public Commands

```console
kast agent verify --workspace-root "$PWD"
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --apply --workspace-root "$PWD"
```

`--symbol <fq-name>` means compiler identity. Use
`kast agent symbol --query <name>` for lookup before mutation.

## Readiness And Repair

`ready` is read-only:

```console
kast ready --for agent --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
```

`repair` is plan-only unless `--apply` is present:

```console
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

Health boundaries:

| Command | Scope |
| --- | --- |
| `kast status` | workspace/runtime state |
| `kast ready --for <target>` | task readiness |
| `kast agent verify` | semantic backend capability |
| `kast developer runtime status` | daemon lifecycle |

## Repository Setup

`kast setup` installs only the v1 repository agent assets:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected context file

```console
kast setup --dry-run --workspace-root "$PWD"
kast setup --workspace-root "$PWD"
kast setup --context-file "$PWD/cli-rs/AGENTS.md" --force
```

The default context target is the first existing file from `AGENTS.md`,
`CODEX.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, or
`AGENTS.local.md`; otherwise setup creates ignored `AGENTS.local.md`.

`kast setup` does not install Copilot package files, portable Markdown
instruction packages, session hooks, generated catalog copies, or workflow helper
assets in v1.

## Removed Surfaces

The old generic surfaces are intentionally removed from the public dialect:

- `kast agent tools`
- `kast agent call`
- `kast agent workflow`
- offset-shaped rename plans

Stale binaries return targeted replacement hints for those commands. Use
`kast help agent` and the installed skill for the current dialect.
