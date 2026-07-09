---
title: How Kast Thinks About Evidence
description: Understand semantic evidence, bounded results, and plan-first edits.
icon: lucide/search-check
---

# How Kast Thinks About Evidence

Kast is useful when text search can show where a spelling appears, but cannot
prove which Kotlin declaration a name resolves to, which callers are real, or
whether a planned edit targets the intended symbol. The command surface is
designed to return compiler-backed evidence before mutation.

## Identity Comes Before Text

Typed agent commands use compiler identity where public workflows need safety.
For example, `--symbol com.example.OrderService` means a compiler-resolved
declaration, not every matching string in the repository.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
```

Resolve broad queries first. Use the selected identity only after the result
matches the target declaration.

## Evidence Can Be Bounded

Reference, caller, hierarchy, and impact evidence may be bounded by depth,
timeout, traversal limits, or source-index availability. A bounded result is
still useful, but readers and agents should treat it differently from an
exhaustive answer.

Use `kast agent verify` before relying on source-index-backed commands in a
fresh or recently refreshed workspace.

```console
kast agent verify --workspace-root "$PWD"
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD" --depth 3
```

## Plans Carry Write Evidence

Mutation commands plan before writing. Plans identify the requested target,
content file, selected scope, and write set so a developer or agent can review
the operation before `--apply`.

```console
kast agent replace-declaration \
  --symbol com.example.OrderService.process \
  --kind function \
  --content-file /tmp/replacement.kt \
  --workspace-root "$PWD"
```

This plan-first shape is part of the public command contract. The CLI should
fail loudly for removed raw surfaces or unsupported selectors instead of
silently falling back to text edits.

## Layers Stay Separate

Kast separates distribution, workspace setup, runtime backends, semantic
commands, and evidence. That separation makes troubleshooting concrete:

| Layer | Question | First check |
| --- | --- | --- |
| Distribution | Is the right binary, plugin, or bundle active? | `kast ready --for machine` |
| Workspace setup | Did the repository receive agent guidance and metadata? | `kast ready --for agent` |
| Runtime backend | Is IDEA or headless analysis reachable? | `kast agent verify` |
| Semantic command | Did the request use typed public flags? | `kast agent --help` |
| Evidence | Is the result complete or bounded? | Command output and diagnostics |

Use the [operating model](../design/operating-model.md) for the full system
boundary, and use [troubleshooting](../troubleshoot.md) when one layer fails.
