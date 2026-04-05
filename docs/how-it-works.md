---
title: How Kast works
description: Understand the architecture, what Kast does, and when to use it
  instead of other approaches.
icon: lucide/layers
---

This page explains what Kast is, how it works under the hood, and when it is
the right tool for the job.

## What Kast is

Kast is a Kotlin analysis tool that exposes semantic code intelligence through
a command-line interface. Instead of opening an IDE, you run `kast` commands
from your shell or automation to resolve symbols, find references, expand call
hierarchies, run diagnostics, and plan renames.

Kast uses the Kotlin K2 Analysis API, the same engine that powers IntelliJ
IDEA's code understanding. This means it understands your code the way the IDE
does — including type inference, inheritance, and cross-module dependencies —
but without the graphical interface.

## When to use Kast

Kast fills the gap between text search and opening an IDE.

| Use this | When you need |
| --- | --- |
| `grep` / `ripgrep` | Fast text matches across files |
| **Kast** | Semantic understanding: "what calls this function?", "where is this symbol defined?", "what breaks if I rename this?" |
| IntelliJ IDEA | Interactive exploration, navigation, and editing |

Kast is the right choice when:

- You need **semantic accuracy** that text search cannot provide. Finding text
  that matches a name is not the same as finding references to a specific
  symbol.
- You are working in **automation or CI**. Kast returns machine-readable JSON
  on stdout, making it scriptable.
- You want analysis **without an IDE**. Useful in terminal workflows, SSH
  sessions, or headless environments.
- You need to **plan refactoring impact**. Kast can show you exactly which
  files and symbols a rename would touch before you apply it.

## Architecture

Kast has a client-server architecture designed for fast repeated use.

```
┌─────────────┐     JSON-RPC      ┌──────────────────────────┐
│   kast CLI  │ ────────────────► │   Standalone Daemon      │
│  (client)   │  Unix domain      │   (backend-standalone)   │
│             │ ◄──────────────── │                          │
└─────────────┘    JSON result    └──────────────────────────┘
                                           │
                                           ▼
                              ┌──────────────────────────┐
                              │  Kotlin K2 Analysis API  │
                              │  + Gradle workspace      │
                              │  discovery               │
                              └──────────────────────────┘
```

The system has four layers:

### `kast` CLI

The command you run. It manages the daemon lifecycle, parses your arguments,
sends requests over a Unix domain socket, and prints JSON results to stdout.
The CLI is the only part you interact with directly.

The client binary resolution follows a small discovery cascade so that users
can override or point at local builds when iterating on the project. By
default the system `PATH` is preferred; you can also set `KAST_CLI_PATH` to an
explicit executable, or set `KAST_SOURCE_ROOT` to point the resolver at local
build outputs (or enable an auto-build when Java 21+ is available). See the
Get started guide for examples.

### `analysis-server`

The transport layer inside the daemon. It handles JSON-RPC protocol, request
limits, timeout behavior, and routes commands to the backend. This layer stays
agnostic about Kotlin specifics.

### `backend-standalone`

The Kotlin-aware runtime. It discovers your Gradle workspace structure,
bootstraps the K2 Analysis API session, and performs the actual semantic
analysis. This is where PSI helpers, compatibility shims, and capability
advertising live.

### `analysis-api`

The shared contract. Serializable request and response models, capability
definitions, error types, and edit-plan validation. This module ensures the
CLI, server, and backend all speak the same language.

## Why a daemon?

Starting a Kotlin analysis session is expensive. The daemon stays alive
between commands so that:

- **Indexes stay warm.** Subsequent commands are fast because the workspace
  is already loaded.
- **Memory is shared.** One process holds the analysis state instead of
  reloading it for every command.
- **Lifecycle is explicit.** You control when the daemon starts and stops
  with `kast workspace ensure` and `kast daemon stop`.

The CLI reuses an existing healthy daemon when one is available, so you do
not need to think about whether to start or stop it manually.

## How workspace state stays fresh

The daemon keeps Kotlin PSI, identifier indexes, and targeted lookup caches in
sync with workspace changes so repeated queries stay accurate.

- **Kast-applied edits refresh immediately.** `kast edits apply` writes files
  to disk, refreshes the affected Kotlin files, and updates the running
  runtime before the command returns.
- **External source changes refresh automatically.** A background watcher
  tracks `.kt` file create, modify, and delete events under the registered
  source roots and batches those updates into daemon refreshes.
- **Manual refresh exists as recovery only.** Use `kast workspace refresh`
  when you need to force a targeted or full rescan after a missed external
  change. Healthy flows do not need this command.

## What Kast can do today

The current capabilities are:

| Capability | CLI command | What it does |
| --- | --- | --- |
| Symbol resolution | `symbol resolve` | Given a file position, return the symbol's fully qualified name, kind, and declaration location |
| Find references | `references` | Given a file position, return all usages of that symbol across the workspace |
| Call hierarchy | `call hierarchy` | Given a file position and direction, return a bounded tree of callers or callees with traversal stats |
| Diagnostics | `diagnostics` | Return compiler and analysis diagnostics for one or more files |
| Rename planning | `rename` | Generate an edit plan showing every location that would change if you renamed a symbol |
| Apply edits | `edits apply` | Apply a prepared edit plan to disk with conflict detection |
| Workspace refresh | `workspace refresh` | Force a targeted or full workspace state refresh when you need manual recovery |

Run `kast capabilities` against your workspace to see what the current runtime
advertises.

## Current limitations

- **Bounded traversal.** `call hierarchy` is intentionally bounded by depth,
  total edges, child count, and optional timeout. Read `stats` and per-node
  `truncation` metadata when you need to know whether the tree is complete.
- **One workspace per daemon.** Each daemon is attached to a single workspace
  root. Run multiple daemons for multiple workspaces.
- **Java 21 required.** The daemon runs on the JVM and needs Java 21 or
  newer.
- **Unix domain sockets.** The default transport uses local sockets, not
  network connections. This is by design for security and performance.

## Next steps

- [Get started](get-started.md) if you are ready to install and run Kast
- [Run analysis commands](run-analysis-commands.md) for task-focused examples
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md) for the
  human-first workflow
