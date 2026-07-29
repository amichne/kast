---
type: Architecture Decision Record
title: IDEA Integration Architecture Decisions
description: Concise decisions that keep IDEA startup, workspace state, graph evidence, and shutdown deterministic.
tags: [internal, idea, architecture, decisions, lifecycle]
code_sources:
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/bootstrap/PluginWorkspaceBootstrap.kt
  - path: cli-rs/src/operations/parts/self_mgmt/macos_workspace.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/startup/KastProjectOpenAutoIndexing.kt
  - path: cli-rs/src/agent/navigation/native_graph/query.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaBackendRuntime.kt
---

# IDEA Integration Architecture Decisions

These decisions describe implemented boundaries. They are short by design;
the linked flow pages contain operational detail.

## Keep workspace state in the global Kast data root

**Status:** Accepted

**Context:** Project-local `.kast` state exposes implementation files to users,
creates repository dirt, and divides configuration from other Kast-owned
workspace data.

**Decision:** Write compatibility metadata to
`workspaceDataDirectory(root)/workspace.json`, beside the workspace's other
global Kast data. Resolve Git repositories from the normalized common Git
directory rather than mutable remote metadata. Atomically migrate only one
unique exact legacy worktree leaf before configuration is read. Do not create
project-local `.kast` state. Remove only the exact legacy metadata file and
parents proven empty and not symbolic links.

**Consequences:** One global root owns Kast state while the normalized project
root remains its identity. Git worktrees stay isolated, origin changes do not
move state, and migration conflicts fail closed. Cleanup cannot remove user
files that happen to share `.kast`.

## Give project-open startup one coordinator

**Status:** Accepted

**Context:** Gradle import, server startup, and indexing have different
asynchronous readiness boundaries. Independent startup activities could race
or start indexing twice.

**Decision:** `KastStartupActivity` delegates to one project-open coordinator.
The coordinator validates bootstrap, starts the backend with indexing disabled,
handles the Gradle decision, and starts or fails indexing from one completion
path. The backend lifecycle retains that Gradle admission through configuration
restart and reports stop completion only after its close future drains.

**Consequences:** The server can report progress while Gradle imports. Indexing
starts once, a failed import produces one readiness failure, and restart cannot
bypass a pending or failed import.

## Use IDEA and Gradle models as source-coverage authority

**Status:** Accepted

**Context:** A recursive filesystem walk is cheap to implement but cannot prove
module ownership, imported source sets, excluded content, or model completion.
It also scales with unrelated files.

**Decision:** Build Kotlin inventory from IDEA project content, file indexes,
and the imported Gradle model. Admit normalized files only inside the exact
workspace root.

**Consequences:** Coverage reflects what IDEA can compile. Incomplete models
fail closed instead of producing an apparently complete graph. Provenance and
inventory share one Gradle model snapshot, and a failed or cancelled refresh
preserves the last committed index generation.

## Collapse analytics links at the SQLite boundary

**Status:** Accepted

**Context:** Topology and community algorithms need endpoints and aggregate
weights, not a copy of every relation kind and context string. Materializing
parallel typed rows wastes memory on large repositories.

**Decision:** Aggregate identical endpoint pairs in SQL for full graph
analytics. Retain the typed occurrence-group count and summed weight. Keep
bounded neighbor queries typed and direct.

**Consequences:** Topology and communities allocate one edge per endpoint pair
without changing summary counts or weighted results. Neighbor output still
identifies relation kind and context.

## Pin every native graph query to one generation

**Status:** Accepted

**Context:** Refresh can replace graph rows while a query enumerates nodes or
computes topology. A mixed snapshot would return internally inconsistent
evidence.

**Decision:** Read the SQLite generation before work and verify it again before
return. Resumed node pages must carry the original generation. Scoped refresh
planning sends that generation to IDEA, and the store commits only when it
still matches. Reject any change.

**Consequences:** Callers retry after concurrent refresh instead of consuming a
mixed graph. Keyset pages, linkages, topology, and communities share the same
consistency contract.

## Drain request ownership before closing shared storage

**Status:** Accepted

**Context:** Transport handlers use the dispatcher and backend. The index worker
and backend share the source-index store. Closing these resources in the wrong
order can leave admitted work using a closed dependency.

**Decision:** Stop transport admission and active clients before dispatcher and
backend close. Cancel and drain admitted request jobs. Cancel indexing, wait for
worker termination, then close the SQLite store. Move the complete blocking
close sequence off IDEA's event dispatch thread.

**Consequences:** Close remains responsive in the user interface and releases
all owned resources. Cleanup continues after individual failures and reports
the complete failure chain.
