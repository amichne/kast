---
type: Runtime Flow
title: Headless Indexing and Generation
description: How one isolated writer reconciles configurable scope and commits resumable graph and reference evidence.
tags: [internal, headless, kotlin, indexing, sqlite, generation, coverage]
code_sources:
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaBackendRuntime.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaProjectIndexing.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/lifecycle/SourceIndexWriterLease.kt
---

# Headless Indexing and Generation

The private shared IntelliJ implementation runs only inside `HeadlessRuntime`.
One index worker reconciles the imported project model, live indexing policy,
persisted file stages, semantic graph facts, and reference facts.

```mermaid
flowchart TD
    demand["Headless indexing demand"] --> lease["Hold exact-root writer lease"]
    lease --> smart["Wait for private IntelliJ smart mode"]
    smart --> inventory["Read IntelliJ and Gradle source inventory"]
    inventory --> policy["Apply hard excludes, .kastignore, and live scope"]
    policy --> reconcile["Reconcile hashes, desired stages, and removals"]
    reconcile --> graph["Extract compiler graph batches"]
    reconcile --> refs["Scan reference batches outside transactions"]
    graph --> commit["Serialize short SQLite transactions"]
    refs --> commit
    commit --> coverage["Publish separate graph and reference coverage"]
    coverage --> changed{"Policy or source changed?"}
    changed -- "yes" --> inventory
    changed -- "no" --> wait["Wait for explicit demand or source event"]
```

## Source scope

Inventory comes from the isolated IntelliJ project and imported Gradle model.
Every path is normalized and must remain inside the canonical root. Build,
`.gradle`, and output roots are hard exclusions. Configuration can narrow
scope but cannot add a hard-excluded path back.

Live `.kastignore`, critical-path, and graph batch-size changes reconcile in
the running process. They do not require a foreground application event or a
runtime restart.

## Durable progress

The store records content hash, stage version, attempt outcome, diagnostics,
and progress per file. Cancellation does not become a durable failure.
Repeated non-cancellation failures remain visible as limited coverage. Scans
run outside write transactions; successful batches commit through one
serialized writer.

Runtime readiness, graph coverage, and reference coverage have separate typed
states. A healthy runtime cannot convert incomplete persisted evidence into a
complete claim.

Continue with [Graph queries](graph-queries.md).
