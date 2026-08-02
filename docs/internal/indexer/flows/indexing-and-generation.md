---
type: Runtime Flow
title: Indexing and Generation
description: How one indexer reconciles source scope and commits resumable graph and reference evidence.
tags: [internal, indexer, kotlin, sqlite, generation, coverage]
code_sources:
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/IndexerServerRuntime.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaProjectIndexing.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
---

# Indexing and Generation

One worker reconciles the imported project model, live indexing policy,
persisted file stages, semantic graph facts, and reference facts.

```mermaid
flowchart TD
    demand["Indexing demand"] --> lease["Hold exact-root writer lease"]
    lease --> smart["Wait for IntelliJ smart mode"]
    smart --> inventory["Read project and Gradle inventory"]
    inventory --> policy["Apply hard excludes and live scope"]
    policy --> reconcile["Reconcile hashes, stages, removals"]
    reconcile --> graph["Extract compiler graph batches"]
    reconcile --> refs["Scan reference batches"]
    graph --> commit["Serialize short SQLite writes"]
    refs --> commit
    commit --> coverage["Publish separate coverage states"]
```

## Source scope

Inventory comes from the isolated IntelliJ project and imported Gradle model.
Every path must remain inside the canonical root. Build, `.gradle`, and output
roots are hard exclusions. Configuration can narrow scope but cannot restore a
hard-excluded path.

Live ignore and batch-size changes reconcile in the running process. They do
not require a foreground application event or a process restart.

## Durable progress

The store records content hash, stage version, attempt outcome, diagnostics,
and progress per file. Cancellation does not become a durable failure.
Repeated failures remain visible as limited coverage. Scans run outside write
transactions; successful batches commit through one serialized writer.

Indexer readiness, graph coverage, and reference coverage have separate typed
states. A healthy process cannot convert incomplete persisted evidence into a
complete claim.

Continue with [Graph queries](graph-queries.md).
