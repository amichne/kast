---
title: CLI cheat sheet
description: Every Kast CLI command, the flags you need most, and what each one returns.
icon: lucide/terminal
---

# CLI cheat sheet

A scannable index of the `kast` CLI. Each table covers one slice
of the surface — workspace lifecycle, read operations, mutations.
Common flags only; run `kast <command> --help` for the full set.

Every command takes `--workspace-root` (absolute path to your
project root) and `--backend-name=standalone` by default. Both
can be set in `kast.toml` so you don't have to repeat them.

## Workspace lifecycle

The daemon owns Kotlin state. These commands start it, watch it,
refresh it, and stop it.

| Command                 | What it does                                                                  | Common flags                                       |
|-------------------------|-------------------------------------------------------------------------------|----------------------------------------------------|
| `kast workspace ensure` | Start the backend if needed and block until indexing finishes.                | `--workspace-root`, `--backend-name`               |
| `kast workspace status` | Report whether a backend is running and what state it's in.                   | `--workspace-root`                                 |
| `kast workspace refresh`| Re-scan disk for files changed outside the daemon.                            | `--workspace-root`, `--file-paths`                 |
| `kast workspace stop`   | Shut the backend down cleanly.                                                | `--workspace-root`                                 |
| `kast capabilities`     | Print which JSON-RPC methods this backend supports.                           | `--workspace-root`                                 |
| `kast health`           | Lightweight liveness ping. Returns immediately.                               | `--workspace-root`                                 |

## Read operations

These commands ask questions about your code. Nothing on disk
changes. Resolve-first applies: most "find X" workflows start
with `kast resolve` to get a stable symbol identity, then feed
the same `--file-path` and `--offset` into the next command.

| Command                  | What it does                                                                | Common flags                                                |
|--------------------------|-----------------------------------------------------------------------------|-------------------------------------------------------------|
| `kast resolve`           | Identify the symbol at a position. Returns FQN, kind, signature, location.  | `--file-path`, `--offset`                                   |
| `kast references`        | Find every reference to the symbol at a position.                           | `--file-path`, `--offset`, `--include-declaration`          |
| `kast call-hierarchy`    | Walk callers (`INCOMING`) or callees (`OUTGOING`) of a function.            | `--file-path`, `--offset`, `--direction`, `--depth`         |
| `kast type-hierarchy`    | Walk supertypes or subtypes of a class or interface.                        | `--file-path`, `--offset`, `--direction`, `--depth`         |
| `kast implementations`   | Find every concrete implementation of an interface or abstract class.       | `--file-path`, `--offset`                                   |
| `kast outline`           | Return a tree of named declarations in a file.                              | `--file-path`                                               |
| `kast workspace-symbol`  | Search for symbols by name across the workspace.                            | `--pattern`, `--regex`, `--limit`                           |
| `kast insertion-point`   | Find a safe position to insert new code into a class or file.               | `--file-path`, `--offset`, `--kind`                         |
| `kast diagnostics`       | Return errors and warnings for one or more files.                           | `--file-paths`                                              |

## Mutations

Mutations always follow plan-then-apply. The first command
computes edits and SHA-256 hashes of the files it read. The
second writes the edits *only if* the hashes still match — the
state `kast` planned against is the state `kast` writes to.

| Command                  | What it does                                                                | Common flags                                                |
|--------------------------|-----------------------------------------------------------------------------|-------------------------------------------------------------|
| `kast rename`            | Plan a rename of the symbol at a position. Returns edits + file hashes.     | `--file-path`, `--offset`, `--new-name`                     |
| `kast optimize-imports`  | Plan import cleanup for one or more files.                                  | `--file-paths`                                              |
| `kast apply-edits`       | Write a previously-planned edit set, rejecting on hash mismatch.            | `--request-file` (JSON file with `edits` + `fileHashes`)    |

## Command tiers

Not every command targets the same audience. `kast` organizes
its surface into two tiers — both fully supported.

**Tier 1 (primary path):** `workspace ensure`, `workspace
status`, `workspace stop`, `capabilities`, `resolve`,
`references`, `diagnostics`, `rename`, `apply-edits`. The default
operational flow — what you reach for first.

**Tier 2 (advanced primitives):** `call-hierarchy`,
`type-hierarchy`, `outline`, `workspace-symbol`,
`insertion-point`, `workspace refresh`, `optimize-imports`.
Specialized building blocks for expert workflows and agent
automation. Stable, supported, less common.

## See also

- [Recipes](recipes.md) — copy-paste workflows that combine
  these commands
- [Understand symbols](what-can-kast-do/understand-symbols.md) —
  long-form reference for the read commands
- [API specification](reference/api-specification.md) — the
  JSON-RPC contract these CLI commands wrap
