---
type: Runtime Flow
title: Graph Refresh and Queries
description: How compiler refresh and generation-pinned projections share one exact-root evidence store.
tags: [internal, indexer, semantic-graph, kotlin, sqlite, generation]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: cli-rs/src/agent/adapter/graph.rs
  - path: cli-rs/src/agent/navigation/native_graph/entrypoint.rs
  - path: cli-rs/src/agent/navigation/native_graph/query.rs
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
---

# Graph Refresh and Queries

Graph refresh and graph reads share one exact-root database but retain
different authority. Refresh needs the admitted indexer and exact mutation
authority. Read-only projections need a valid database, scope proof, coverage,
and one pinned generation.

```mermaid
flowchart LR
    subgraph Refresh["Compiler-backed refresh"]
        scope["Resolve model-proven scope"] --> authority["Admit indexer"]
        authority --> extract["Extract compiler facts"]
        extract --> write["Replace selected files"]
        write --> generation["Commit one generation"]
    end

    subgraph Query["Read-only projection"]
        open["Open SQLite read-only"] --> pin["Validate coverage and generation"]
        pin --> project["Run bounded projection"]
        project --> recheck["Recheck generation"]
        recheck -- "unchanged" --> result["Return result and limits"]
        recheck -. "changed" .-> reject["Reject mixed evidence"]
    end

    generation --> open
```

The CLI normalizes and deduplicates selected files. Module and source-set
selectors require persisted Gradle ownership. The indexer requires analyzed
diagnostics, extracts canonical identities and typed occurrences, and passes
provider-neutral facts to `SemanticGraphWriter`.

Nodes use bounded keyset pagination. Neighbors use degree-bounded SQL reads.
Topology and communities materialize only the requested quotient graph. Every
result retains generation, scope, coverage, bounds, and limitations.

Coverage accounts for stale, failed, excluded, and unproven files. A non-empty
result does not prove completeness. Generation movement before return is a
typed consistency failure.

Continue with [Shutdown](shutdown.md).
