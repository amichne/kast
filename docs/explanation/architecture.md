---
type: Explanation
title: Kast Architecture
description: How setup, exact-root indexer admission, compiler evidence, and typed results fit together.
tags: [architecture, agents, indexer, runtime]
code_sources:
  - path: cli-rs/src/main.rs
  - path: cli-rs/src/operations/install/agent_resources.rs
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/backend/AnalysisBackend.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerRuntime.kt
---

# Kast Architecture

Kast separates its public agent interface, installation authority, exact-root
indexer, and persisted evidence. One verified release can serve many
workspaces without treating an installed binary as proof that compiler
evidence is ready.

The interactive landscape starts at Kast's public boundary. Select Kast to
drill into its components, or use the browser to move between related views.

<kast-view view-id="system-landscape" browser="true"></kast-view>

## Setup chooses one release

`install.sh` invokes the release-local `libexec/kastctl setup`. Setup validates
and stages the complete bundle before it switches the active `current` link.
The same executable bytes provide `libexec/kastctl` for administration and
`bin/kast` for agents; the invoked name selects the command grammar. Only
`kast` is linked onto `PATH`.

The bundle contains the CLI, the matched indexer, and release-matched Codex,
Claude, and Copilot resources. Harness resources adapt external events into
`kast` commands. They do not become installation or semantic authorities.

## Admission chooses one exact workspace

For each semantic task, `kast` discovers and normalizes the nearest Gradle
workspace. Agents do not choose an implementation, schema version, transport,
or output protocol.

Kast first looks for an eligible healthy indexer bound to that canonical root.
It reuses an exact match. If none exists, it creates one isolated indexer. A
conflicting identity produces a typed failure instead of a guess.

Each root has separate configuration, VFS state, descriptors, leases, sockets,
and indexes. On macOS, a supported IntelliJ IDEA or Android Studio installation
supplies compatible libraries. Kast does not install into or control the
foreground application.

<kast-view view-id="indexer-runtime" browser="true"></kast-view>

## The indexer owns compiler truth

The indexer owns the imported Gradle model, Kotlin PSI, compiler analysis,
reference indexing, and semantic graph production. Admission remains pending
until Kotlin modules, SDKs, dependencies, PSI, and diagnostics are usable.

`INDEXING` proves that the process is reachable. `READY` additionally requires
Gradle settlement, IntelliJ smart mode, Kotlin semantic admission, and Kast
reference-index completion. A failed phase produces a typed actionable cause.

`READY` does not prove complete persisted graph coverage. Every operation
reports that evidence separately.

The indexer returns the shared Kotlin models defined by `analysis-api`.
Callers consume typed symbols, relationships, diagnostics, edits, and coverage
instead of IntelliJ-specific objects.

## Results carry their limits

Kast preserves exact paths and symbol identities when it projects indexer
results into compact CLI views. It also preserves limitations. Indexing,
unavailable source modules, bounded relationship results, and unsupported
capabilities cannot become an empty success.

Eligible file-local failures remain visible without aborting unrelated files.
An explicitly accepted content-bound failure becomes an `UNKNOWN` graph
boundary. Cancellation, corruption, protocol, and infrastructure failures
remain terminal.

`kast graph` adds generation-pinned traversal, topology, communities, and
impact over persisted compiler evidence. See
[Repository intelligence](repository-intelligence.md) for that authority
chain, or [How Kast works](../internal/system-flow.md) for the source-backed
command map.
