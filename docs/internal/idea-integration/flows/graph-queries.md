---
type: Runtime Flow
title: Headless Graph Refresh and Queries
description: How admitted compiler refresh and generation-pinned read-only projections share one exact-root evidence store.
tags: [internal, headless, semantic-graph, graph, kotlin, sqlite, generation]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/headless_authority.rs
  - path: cli-rs/src/agent/adapter/graph.rs
  - path: cli-rs/src/agent/navigation/native_graph/entrypoint.rs
  - path: cli-rs/src/agent/navigation/native_graph/query.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
---

# Headless Graph Refresh and Queries

Graph refresh and graph reads share one exact-root database but retain
different authority. Refresh needs an admitted headless runtime and an exact
authenticated mutation lease. Read-only projections need a valid database,
scope proof, coverage, and one pinned generation.

```mermaid
flowchart LR
    subgraph Refresh["Compiler-backed refresh"]
        scope["Resolve model-proven source scope"] --> authority["Admit headless runtime and lease"]
        authority --> extract["Extract compiler facts in private read actions"]
        extract --> write["Conditionally replace selected files"]
        write --> generation["Commit one new generation"]
    end

    subgraph Query["Read-only projection"]
        open["Open SQLite read-only"] --> pin["Validate schema, coverage, and generation"]
        pin --> project["Run bounded nodes, neighbors, topology, or communities"]
        project --> recheck["Recheck generation"]
        recheck -- "unchanged" --> result["Return typed result and limits"]
        recheck -. "changed" .-> reject["Reject mixed-generation evidence"]
    end

    generation --> open
```

## Refresh authority

The CLI normalizes and deduplicates selected files. Module, source-set, and
exclusive selectors require persisted Gradle ownership. The central admission
boundary rejects retired IDEA intent before the RPC or lease side effect.

The private compiler implementation requires analyzed diagnostics, extracts
canonical identities and typed occurrences, and passes provider-neutral facts
to `SemanticGraphWriter`. The writer replaces the selected files atomically.
An error rolls back rows and generation together.

## Query authority

Nodes use bounded keyset pagination. Neighbors use degree-bounded SQL reads.
Topology and communities materialize only the requested quotient graph. Every
result retains generation, scope, coverage, bounds, and limitations.

Coverage must account for stale, failed, excluded, and unproven files. A
non-empty result does not prove completeness. A generation movement before the
result returns is a typed consistency failure.

Continue with [Shutdown](shutdown.md).
