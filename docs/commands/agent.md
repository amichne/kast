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
kast agent add-file --file-path "$PWD/src/main/kotlin/NewType.kt" --content-file /tmp/NewType.kt --workspace-root "$PWD"
kast agent add-declaration --inside-file "$PWD/src/main/kotlin/App.kt" --at file-bottom --content-file /tmp/declaration.kt --workspace-root "$PWD"
kast agent add-implementation --inside-scope com.example.Service --at body-end --content-file /tmp/member.kt --workspace-root "$PWD"
kast agent add-statement --inside-scope com.example.Service.process --at body-end --content-file /tmp/statement.kt --workspace-root "$PWD"
kast agent replace-declaration --symbol com.example.Service.process --kind function --content-file /tmp/replacement.kt --workspace-root "$PWD"
```

`--symbol <fq-name>` means compiler identity. Use
`kast agent symbol --query <name>` for lookup before mutation.

## Mutations

Agent mutations are plan-first. Without `--apply`, mutation commands emit a
structured request plan and do not write files. Add `--apply` only after
reviewing the generated request and content file.

| Command | Selector | Placement |
| --- | --- | --- |
| `add-file` | `--file-path` | complete file from `--content-file` |
| `add-declaration` | `--inside-file` or `--inside-scope` | `--at`, `--after-symbol`, or `--before-symbol` |
| `add-implementation` | `--inside-file` or `--inside-scope` | `--at`, `--after-symbol`, or `--before-symbol` |
| `add-statement` | `--inside-scope` | `--at body-end` |
| `replace-declaration` | `--symbol` plus optional `--kind`, `--file-hint`, `--containing-type` | replaces the resolved declaration scope |

Shared anchors are `file-top`, `file-bottom`, `after-imports`, `body-start`,
and `body-end` where they apply to the selected scope.

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

On macOS, repository setup is prepared by the IntelliJ plugin after the
workspace opens. `kast setup` fails closed there so the CLI cannot create
skill-only or resource-only partial state.

On non-macOS headless/server installs, `kast setup` installs only the
repository agent assets:

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
assets.

## Removed Surfaces

The old generic surfaces are intentionally removed from the public dialect:

- `kast agent tools`
- `kast agent call`
- `kast agent workflow`
- offset-shaped rename plans

Stale binaries return targeted replacement hints for those commands. Use
`kast help agent` and the installed skill for the current dialect.
