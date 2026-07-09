---
title: Agent Commands
description: Reference for the typed Kast agent command surface.
icon: lucide/bot
---

# Agent Commands

`kast agent` is the typed, machine-oriented surface for compiler-backed Kotlin
work. It is the public path for agents and scripts that need semantic evidence
or plan-first Kotlin mutations.

## Command List

The current agent commands are:

| Command | Role | Primary required flags |
| --- | --- | --- |
| `kast agent verify` | Verify backend health, runtime state, capabilities, and workspace root | none |
| `kast agent symbol` | Resolve a symbol and optionally gather references or callers | `--query <query>` |
| `kast agent impact` | Query source-index impact for a fully qualified symbol | `--symbol <fq-name>` |
| `kast agent diagnostics` | Refresh touched files and run diagnostics | `--file-path <path>` |
| `kast agent rename` | Rename a compiler-resolved symbol by identity | `--symbol <fq-name>`, `--new-name <name>` |
| `kast agent add-file` | Create a new Kotlin file from complete content | `--file-path <path>`, `--content-file <path>` |
| `kast agent add-declaration` | Add a declaration inside a file or named scope | `--content-file <path>` plus a selector |
| `kast agent add-implementation` | Add implementation content inside a file or named scope | `--content-file <path>` plus a selector |
| `kast agent add-statement` | Add a statement inside a named executable scope | `--inside-scope <fq-name>`, `--at body-end`, `--content-file <path>` |
| `kast agent replace-declaration` | Replace a named declaration by symbol identity | `--symbol <fq-name>`, `--content-file <path>` |
| `kast agent lsp` | Run the LSP adapter for editor integration | `--stdio` |

All agent commands accept `--output <human|json|toon>`. Semantic commands also
accept `--workspace-root <path>` and `--backend <idea|headless>`.

## Verification

`verify` checks the semantic backend before an agent depends on symbol,
diagnostics, impact, or mutation answers.

```console
kast agent verify --workspace-root "$PWD"
```

Verification reports backend health, runtime state, advertised capabilities,
and the workspace root the backend is serving.

## Symbol Lookup

`symbol` starts from query text. Use lookup results to choose compiler identity
before running mutation commands.

| Flag | Meaning |
| --- | --- |
| `--query <query>` | Symbol query text |
| `--kind <class|interface|object|function|property>` | Restrict candidates by declaration kind |
| `--file-hint <path>` | Prefer candidates associated with a file |
| `--containing-type <fq-name>` | Restrict candidates to a containing type |
| `--references` | Include references for the resolved symbol |
| `--callers <incoming|outgoing>` | Include caller or callee evidence |
| `--caller-depth <n>` | Bound caller traversal, default `3` |
| `--limit <n>` | Bound returned candidates, default `10` |

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
```

## Diagnostics And Impact

Diagnostics refresh touched files unless `--skip-refresh` is present.

```console
kast agent diagnostics \
  --file-path "$PWD/src/main/kotlin/App.kt" \
  --workspace-root "$PWD"
```

Impact requires a fully qualified symbol and reads source-index evidence.

```console
kast agent impact \
  --symbol com.example.OrderService \
  --depth 3 \
  --limit 50 \
  --workspace-root "$PWD"
```

Impact results may be bounded by index state, depth, limit, traversal budget,
or backend availability.

## Plan-First Mutations

Mutation commands plan by default. `--apply` is the explicit mutation gate.

```console
kast agent rename \
  --symbol com.example.OrderService \
  --new-name Orders \
  --workspace-root "$PWD"

kast agent rename \
  --symbol com.example.OrderService \
  --new-name Orders \
  --apply \
  --workspace-root "$PWD"
```

Use [mutation selectors](mutation-selectors.md) for add and replace selector
facts.

## LSP Adapter

The LSP adapter remains available for editor integration.

```console
kast agent lsp --stdio --workspace-root "$PWD"
```

Agent automation should prefer typed `kast agent` commands when it needs
semantic facts or edit plans.
