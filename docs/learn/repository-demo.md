---
title: Explore your repository
description: Learn Kast through compiler and source-index evidence from your own Kotlin codebase.
icon: lucide/presentation
---

# Explore your repository

`kast demo` turns evidence from the selected Kotlin repository into a guided,
read-only semantic story. It chooses concrete symbols from the source index,
adds compiler evidence when a backend is ready, and ends each chapter with the
typed `kast agent` command that reproduces the result.

## Start a repository tour

Run the command from a plugin-prepared or headless Kast workspace:

```console
kast demo --workspace-root "$PWD"
```

In a human terminal, Kast opens an interactive story. Use the arrow keys to
select a candidate and move through the chapters. Captured output never emits
terminal control sequences; it returns a deterministic structured snapshot.

Use `--symbol` when you already know what should anchor the story:

```console
kast demo --symbol com.example.OrderService --workspace-root "$PWD"
```

## What the story shows

The available chapters depend on evidence that Kast can prove in the current
workspace. Missing evidence disables a chapter explicitly.

| Chapter | Evidence |
| --- | --- |
| Identity | The compiler-resolved declaration and source location |
| Why semantics | Lexical matches compared with semantic symbol results |
| Relationships | References and callers around the selected symbol |
| Impact | Source-index evidence for the code affected by a change |
| Safety | Diagnostics plus a plan-only hypothetical rename preview |
| Recap | Equivalent typed `kast agent` commands |

Selecting **explore graph** hands an impact or call-chain story to the symbol
walk. A semantic-ambiguity story opens the side-by-side lexical and semantic
comparison.

## Evidence modes

The response names its evidence mode so a human or script can distinguish a
complete story from a degraded one.

| Mode | Available evidence | Behavior |
| --- | --- | --- |
| `full` | Source index and ready compiler backend | All supported chapters |
| `indexOnly` | Source index | Ranked stories without compiler-only chapters |
| `backendOnly` | Ready compiler backend | Requires `--symbol`; index-derived chapters stay unavailable |
| Unavailable | Neither evidence lane | Fails with platform-specific setup guidance |

The demo inspects an already available backend. It does not start, install, or
repair a runtime as a side effect.

## Read-only boundary

The demo is read-only and reports `mutates: false` in structured output. The
safety chapter can validate a hypothetical Kotlin name and show a rename
request, but it never exposes or invokes `--apply` and never changes source
files.

## Structured output

Use the global output option when a script, evaluation harness, or captured
agent session needs stable data:

```console
kast --output json demo --workspace-root "$PWD"
```

The snapshot includes the evidence mode, ranked candidates, selected compiler
story when available, chapter availability, warnings, and exact follow-up
commands.

## Continue with typed commands

Use the recap as a handoff from exploration to repeatable automation:

```console
kast agent symbol --query com.example.OrderService --references --workspace-root "$PWD"
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD"
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```
