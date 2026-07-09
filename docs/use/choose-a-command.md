---
title: Choose A Command
description: Pick the Kast command family that matches the reader job.
icon: lucide/list-tree
---

# Choose A Command

Use this guide when you know the job but not the Kast command family. Kast
keeps public workflows on typed commands: setup, readiness, runtime lifecycle,
Kotlin inspection, safe edits, and release work each have a different entry
point.

## Start With The Job

| Reader job | Start with | Why |
| --- | --- | --- |
| Print workspace context for an agent | `kast context` | Shows compact context and command hints |
| Check whether a task surface is ready | `kast ready --for <target>` | Read-only readiness by task surface |
| Repair install or guidance drift | `kast repair` then `kast repair --apply` | Plan before mutation |
| Check runtime state | `kast status` | Workspace status without choosing a developer subcommand |
| Start or inspect a backend | `kast developer runtime ...` | Runtime lifecycle and capabilities |
| Inspect Kotlin semantically | `kast agent symbol`, `diagnostics`, or `impact` | Compiler-backed evidence |
| Plan safe Kotlin edits | `kast agent rename` or mutation commands | Identity-first, plan-before-apply edits |
| Run an editor adapter | `kast agent lsp --stdio` | LSP bridge for editor integration |
| Package or verify release artifacts | `kast developer release ...` | Release engineering commands |

Use [command surface reference](../reference/commands.md) when you need the
curated command group list.

## Use Readiness Before Repair

Readiness is read-only. Run it before applying repair, especially in automation.

```console
kast ready --for agent --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
```

Repair plans by default and mutates only with `--apply`.

```console
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

## Use Agent Commands For Kotlin Work

Use typed `kast agent` commands when text search is not enough.

```console
kast agent verify --workspace-root "$PWD"
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```

Prefer typed agent commands over raw transport, generated catalog lookup, byte
offset selectors, or implementation class names.

## Use Developer Commands For Operators

Developer commands inspect or manage runtime, machine, release, and generated
contract surfaces. They are public operator commands, but they should not
become the default agent automation path.

```console
kast developer runtime status --workspace-root "$PWD"
kast developer inspect paths
kast developer release validate --help
```

Use [runtime and output modes](../reference/runtime-and-output.md) for backend
selection and structured output behavior.
