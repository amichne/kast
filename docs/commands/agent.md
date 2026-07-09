---
title: Agent Commands
description: Use typed `kast agent` commands for compiler-backed Kotlin work.
icon: lucide/bot
---

# Agent Commands

`kast agent` is the typed, machine-oriented surface for agents and scripts. It
defaults to compact TOON; pass `--output json` when a script needs JSON.
The V1 surface is `kast agent verify`, `kast agent symbol`,
`kast agent diagnostics`, `kast agent impact`, `kast agent rename`, mutation
commands, and `kast agent lsp`.

## Start With Readiness

Run `verify` before relying on semantic answers. It checks backend health,
runtime state, capabilities, and the workspace root the backend is serving.

```console
kast agent verify --workspace-root "$PWD"
```

If the backend is missing or stale, repair the install state first, then start
or refresh the runtime through the lifecycle commands.

## Inspect Kotlin

Inspection commands answer compiler-backed questions. Start broad with a symbol
query, then ask for references, callers, impact, or diagnostics when needed.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD"
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```

!!! tip "Resolve before mutation"
    `--symbol <fq-name>` means compiler identity, not a text match. Use
    `kast agent symbol --query <name>` before any command that changes code.

## Rename By Identity

Rename is plan-first. The first command shows the write set and conflicts; the
second applies the same request only after you opt in.

```console
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --apply --workspace-root "$PWD"
```

Offset-shaped local rename plans are not part of the public dialect. Use named
declaration identities until typed local selectors are available.

## Plan Scope Mutations

Mutation commands read Kotlin content from files and plan the edit before
writing. This keeps shell quoting out of the code path and gives agents a stable
request to review.

=== "Create file"

    ```console
    kast agent add-file \
      --file-path "$PWD/src/main/kotlin/NewType.kt" \
      --content-file /tmp/NewType.kt \
      --workspace-root "$PWD"
    ```

=== "Insert declaration"

    ```console
    kast agent add-declaration \
      --inside-file "$PWD/src/main/kotlin/App.kt" \
      --at file-bottom \
      --content-file /tmp/declaration.kt \
      --workspace-root "$PWD"
    ```

=== "Insert implementation"

    ```console
    kast agent add-implementation \
      --inside-scope com.example.Service \
      --at body-end \
      --content-file /tmp/member.kt \
      --workspace-root "$PWD"
    ```

=== "Insert statement"

    ```console
    kast agent add-statement \
      --inside-scope com.example.Service.process \
      --at body-end \
      --content-file /tmp/statement.kt \
      --workspace-root "$PWD"
    ```

=== "Replace declaration"

    ```console
    kast agent replace-declaration \
      --symbol com.example.Service.process \
      --kind function \
      --content-file /tmp/replacement.kt \
      --workspace-root "$PWD"
    ```

Add `--apply` to any mutation command only after reviewing the planned request
and the content file.

## Mutation Selectors

Selectors describe where the content belongs. Prefer the narrowest selector
that matches the intent.

| Command | Selector | Placement |
| --- | --- | --- |
| `add-file` | `--file-path` | Complete file from `--content-file` |
| `add-declaration` | `--inside-file` or `--inside-scope` | `--at`, `--after-symbol`, or `--before-symbol` |
| `add-implementation` | `--inside-file` or `--inside-scope` | `--at`, `--after-symbol`, or `--before-symbol` |
| `add-statement` | `--inside-scope` | `--at body-end` |
| `replace-declaration` | `--symbol` plus optional `--kind`, `--file-hint`, `--containing-type` | Replace the resolved declaration scope |

Shared anchors are `file-top`, `file-bottom`, `after-imports`, `body-start`,
and `body-end` where they apply to the selected scope.

## Readiness And Repair

`ready` is read-only, while `repair` is plan-only unless `--apply` is present.
Use both before changing repository guidance or managed install state.

```console
kast ready --for agent --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

Health boundaries:

| Command | Scope |
| --- | --- |
| `kast status` | Workspace/runtime state |
| `kast ready --for <target>` | Task readiness |
| `kast agent verify` | Semantic backend capability |
| `kast developer runtime status` | Daemon lifecycle |

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
`CODEX.md`, `CLAUDE.md`, or `AGENTS.local.md`; otherwise setup creates ignored
`AGENTS.local.md`.

`kast setup` does not install Copilot package files, portable Markdown
instruction packages, session hooks, generated catalog copies, or workflow
helper assets.
