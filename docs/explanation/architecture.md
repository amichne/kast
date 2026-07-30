---
type: Explanation
title: Kast Architecture
description: How setup, agent routing, exact-root runtime admission, compiler backends, and typed results fit together.
tags: [architecture, agents, idea, headless, runtime]
code_sources:
  - path: cli-rs/src/main.rs
  - path: cli-rs/src/operations/install/agent_resources.rs
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/backend/AnalysisBackend.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt
---

# Kast Architecture

Kast separates its public agent interface, internal installation authority,
and semantic runtime authority. That lets one verified release serve multiple
exact workspaces without pretending that installing a binary also proves a
compiler is ready.

The interactive landscape starts at Kast's public boundary. Select Kast to
drill into the runtime components, or open the diagram browser to move between
related views.

<kast-view view-id="system-landscape" browser="true"></kast-view>

## Setup chooses one release

`install.sh` invokes the private release-local `libexec/kastctl setup` to stage
a manifest-bound release containing the multicall executable and matched
backend artifacts. The same bytes are installed as `libexec/kastctl` for
administrative automation and `bin/kast` for agents; the invoked name selects
the grammar. Only `kast` is linked onto `PATH`. Setup verifies the complete
release before switching the active `current` link.

Release-matched Codex, Claude, and Copilot resources are embedded in that
executable and registered from a digest-owned local directory. Their skill and
hooks expose only `kast`; they do not become another semantic backend.

## Admission chooses one exact workspace

For each semantic task, `kast` discovers and normalizes the nearest Gradle
workspace, then selects a compatible backend for that exact root. Agents do
not supply backend names, output modes, schema versions, or transport details.

Automatic routing accepts a single ready candidate. Multiple ready candidates
produce an actionable ambiguity instead of a guess. A mutation additionally
requires prepared workspace authority, so Kast does not apply compiler-based
edits through a runtime attached to a different checkout.

On macOS, admission also owns the normal project-opening path:

<kast-view view-id="macos-runtime" browser="true"></kast-view>

The IDE application process may host several worktrees, but each canonical
root has separate metadata, descriptors, leases, sockets, and indexes. A
private one-shot request lets only the selected plugin process open the
requested root and prevents duplicate project frames.

Kast requests background launch and never calls focus APIs. It preserves the
active IDEA frame's public placement where possible; fullscreen, display, and
native project-tab behavior remain macOS and JetBrains responsibilities.

## The backend owns compiler truth

On macOS, the IDEA plugin owns project models, Kotlin PSI, indexing, and
compiler analysis. Its semantic admission remains pending until Kotlin modules,
SDKs, dependencies, PSI, and diagnostics are usable. On supported non-IDE
hosts, the packaged headless backend implements the same analysis contract.

The macOS runtime reports `INDEXING` as soon as the exact server is reachable.
It reports `READY` only after Gradle completion, IDEA smart mode, Kotlin
semantic admission, and Kast reference-index completion. One failed phase
produces `DEGRADED` with an actionable cause.

`READY` proves that direct compiler operations can run. Persisted semantic
graph coverage is reported separately and can still be incomplete.

Both backends return the shared Kotlin models defined by `analysis-api`.
Callers therefore consume typed symbols, relationships, diagnostics, edits,
and coverage instead of backend-specific PSI objects.

## Results carry their limits

Kast projects backend results into compact CLI views. Exact paths and symbol
identity survive that projection. So do limitations: indexing, unavailable
source modules, missing reference indexes, bounded relationship results, and
unsupported capabilities remain visible instead of being converted into an
empty success.

Reference indexing retains eligible file-local failures instead of aborting
the entire pass. When an agent explicitly externalizes a content-bound failure
identifier, Kast atomically clears unsupported outgoing facts and records the
file as an `UNKNOWN` graph boundary. Inbound references may still point to
retained boundary symbols. Cancellation, corruption, and infrastructure
failures remain terminal.

The graph path adds generation-pinned traversal, topology, communities, and
impact over persisted compiler evidence. See
[Repository intelligence architecture](repository-intelligence.md) for the
underlying authority chain and current operating limits.

For the complete public-command map and its source anchors, continue to
[How Kast works](../internal/system-flow.md).
