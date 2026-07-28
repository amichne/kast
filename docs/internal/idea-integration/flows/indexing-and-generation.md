---
type: Runtime Flow
title: IDEA Indexing and Generation
description: How Kast waits for semantic admission, inventories Kotlin through IDEA models, and publishes generation-backed SQLite evidence.
tags: [internal, idea, kotlin, indexing, sqlite, generation]
code_sources:
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaBackendRuntime.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaIndexSemanticAdmission.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/semantic/references/IdeaReferenceIndexEnvironment.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
---

# IDEA Indexing and Generation

`KastIdeaProjectIndexing` owns one index attempt for the running backend. A
compare-and-set guard makes `start()` idempotent. The worker does not run until
IDEA leaves dumb mode and semantic admission accepts the project model.

```mermaid
flowchart TD
    request["startIndexing()"] --> once{"First start request?"}
    once -- "no" --> return["Return"]
    once -- "yes" --> smart["DumbService.runWhenSmart"]
    smart --> cancelled{"Cancelled or project disposed?"}
    cancelled -- "yes" --> terminated["Signal terminated"]
    cancelled -- "no" --> worker["Start one index worker"]
    worker --> admission["Wait for semantic admission"]
    admission --> hydrate["Attempt remote snapshot hydration"]
    hydrate --> inventory["Read IDEA and Gradle source inventory"]
    inventory --> scan["Scan admitted Kotlin files"]
    scan --> persist["Write files, declarations, and references"]
    persist --> summary["Read summary and mark complete"]
    summary --> publish["Publish reusable repository snapshot when configured"]

    admission -. "typed failure" .-> failed["Record failed readiness"]
    inventory -. "incomplete model" .-> failed
    scan -. "compiler or store failure" .-> failed
    worker -. "cancel" .-> terminated
```

## Exact-root source inventory

The indexer derives candidates from IDEA project content and the imported
Gradle model. It normalizes each path and admits only files inside the exact
workspace root. Kotlin reference scanning uses IDEA indexes and project model
inventory; it does not recursively walk the repository.

One bridge read supplies the Gradle model to both provenance and file
inventory. The indexer rejects an incomplete imported model before it reads
project files or replaces the committed index. A cancellation observed after
scanning also returns before the full-index save, so the previous manifest and
generation remain intact.

This boundary improves both speed and accuracy:

- generated output and unrelated nested repositories do not enter by accident;
- Gradle module and source-set identities stay attached to each file;
- IDEA decides which source files belong to the loaded project;
- cancellation can stop bounded batches without traversing the whole disk.

## SQLite ownership

`KastIdeaBackendRuntime` creates one `SqliteSourceIndexStore` from the exact
`WorkspaceIdentity`. The backend, indexer, reference lookup, and semantic graph
writer share that store while the runtime is alive.

The store contains two related forms of evidence:

1. Source inventory, module progress, declarations, and reference rows describe
   which Kotlin files the project index covered.
2. Semantic graph files, symbols, types, and relation occurrences describe
   compiler-resolved graph facts for refreshed files.

Graph file replacement is transactional. `SemanticGraphWriter` removes stale
rows, writes the complete replacement set, increments the source-index
generation inside the same transaction, and then commits. A failed write rolls
back both data and generation.

```mermaid
sequenceDiagram
    autonumber
    participant Worker as IDEA index worker
    participant Admission as Semantic admission
    participant Inventory as Project and Gradle inventory
    participant Scanner as Kotlin scanners
    participant Store as SQLite source index
    participant Graph as Semantic graph writer

    Worker->>Admission: await(cancelled)
    Admission-->>Worker: exact-root model admitted
    Worker->>Inventory: read one complete Gradle model snapshot
    Inventory->>Inventory: derive provenance and model-owned Kotlin files
    Inventory-->>Worker: paths plus module evidence
    loop bounded Kotlin work
        Worker->>Scanner: analyze admitted file
        Scanner->>Store: replace declarations and references
    end
    Worker->>Store: mark module progress complete
    Note over Graph,Store: Later graph refresh shares the same store
    Graph->>Store: begin transaction
    Graph->>Store: replace graph files, symbols, types, relations
    Graph->>Store: increment generation
    Graph->>Store: commit
```

## Generation meaning

A generation identifies a committed view of the shared source-index database.
It is not a timestamp and it is not inferred from file modification times.
Graph readers pin this value before enumeration or computation and verify it
again before returning.

The next page follows that contract through
[graph refresh and queries](graph-queries.md).
