---
type: Runtime Flow
title: Native Graph Refresh and Queries
description: How the CLI refreshes compiler-backed graph evidence and reads nodes, linkages, topology, and communities from one generation.
tags: [internal, semantic-graph, graph, kotlin, sqlite, generation]
code_sources:
  - path: cli-rs/src/agent/navigation/native_graph/entrypoint.rs
  - path: cli-rs/src/agent/navigation/native_graph/query.rs
  - path: cli-rs/src/agent/navigation/native_graph/base_graph.rs
  - path: cli-rs/src/agent/navigation/native_graph/overlay_graph.rs
  - path: cli-rs/src/agent/navigation/native_graph/neighbors.rs
  - path: cli-rs/src/agent/navigation/native_graph/graph_algorithms.rs
  - path: cli-rs/src/agent/navigation/native_graph/partitions.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphExtraction.kt
---

# Native Graph Refresh and Queries

The native graph command has two phases. Refresh asks the admitted IDEA backend
to extract compiler facts for a closed file scope. Query opens the resulting
SQLite database read-only and computes a bounded projection from one pinned
generation.

```mermaid
flowchart LR
    subgraph Refresh["Compiler-backed refresh"]
        select["Resolve exact source scope and generation"] --> rpc["raw/semantic-graph RPC with expected generation"]
        rpc --> diagnostics["Require analyzed Kotlin diagnostics per file"]
        diagnostics --> extract["Extract one cancellable PSI read epoch per file"]
        extract --> write["Conditional transactional SQLite replacement"]
        write --> generation["Commit new generation"]
    end

    subgraph Query["Read-only native query"]
        open["Open query-only SQLite connection"] --> pin["Read and validate generation"]
        pin --> operation{"Operation"}
        operation --> nodes["Keyset node page from SQL"]
        operation --> links["Degree-bounded linkages from SQL"]
        operation --> project["Materialize requested quotient graph"]
        project --> topology["Components, strongly connected components, order"]
        project --> communities["Weighted Leiden communities"]
        nodes --> recheck["Recheck generation"]
        links --> recheck
        topology --> recheck
        communities --> recheck
    end

    generation --> open
    recheck -. "changed" .-> reject["Reject mixed-generation result"]
    recheck -- "unchanged" --> result["Return typed bounded result"]
```

## Refresh

`execute_agent_native_graph_refresh` normalizes and deduplicates the requested
files. Module, source-set, and exclusive selectors are accepted only when one
persisted source-index snapshot proves Gradle ownership. The CLI retains that
snapshot's generation and sends it as `expectedGeneration` through a
READY exact-root runtime session.

Inside IDEA, `semanticGraphOperation`:

1. requires the semantic graph capability and current SQLite schema;
2. translates every selected and removed path to a workspace-relative path;
3. rejects source outside the active workspace;
4. requires analyzed Kotlin diagnostics for every refreshed file;
5. extracts compiler-resolved symbols, types, owners, containment, calls,
   inheritance, implementation, delegation, and type-reference occurrences;
6. classifies out-of-scope compiler targets as external evidence;
7. verifies that every per-file PSI read observed one project generation;
8. replaces all affected graph rows only if the source-index generation still
   matches the planned snapshot.

The refresh response retains file coverage, omitted external target count,
symbol count, relation occurrence count, scope fingerprint, and committed
generation. Cancellation or either generation conflict leaves the prior
committed graph intact.

```mermaid
sequenceDiagram
    autonumber
    participant CLI as _kastctl agent graph refresh
    participant Scope as Persisted source scope
    participant Runtime as Exact-root runtime
    participant RPC as raw/semantic-graph
    participant IDEA as IDEA semantic backend
    participant Compiler as Kotlin Analysis API
    participant Store as SQLite graph store

    CLI->>Scope: resolve files from one source-index snapshot
    Scope-->>CLI: normalized Kotlin paths plus generation
    CLI->>Runtime: require READY exact workspace
    Runtime->>RPC: refresh paths with expected generation
    RPC->>IDEA: semanticGraph(query)
    loop each selected file
        IDEA->>Compiler: one cancellable read epoch
        Compiler-->>IDEA: canonical symbols and relations
    end
    IDEA->>IDEA: verify one PSI generation
    IDEA->>Store: replace if source-index generation matches
    Store->>Store: increment generation and commit
    Store-->>IDEA: counts and generation
    IDEA-->>CLI: coverage plus scope fingerprint
```

## Query operations

| Operation | Result |
| --- | --- |
| `nodes` | A bounded SQL keyset page of canonical symbol identities. Resuming requires both `after-id` and the original generation. |
| `neighbors` | Degree-bounded incoming and outgoing SQL rows for one node, including relation kind, context, weight, and peer identity. |
| `summary` | Node and edge counts, connected components, strongly connected components, community count, timings, database size, and process memory evidence. |
| `topology` | Node keys, connected-component membership, strongly connected-component membership, and condensation topological order. |
| `communities` | A deterministic weighted Leiden partition at the requested resolution. |

## Scopes and quotient graphs

| Scope | Nodes | Edges |
| --- | --- | --- |
| Symbol | Canonical semantic symbols | Weighted links between source and target symbols |
| File | Semantic source files | Weighted links between source and target files |
| Package | Package identities | Weighted links between source and target packages |
| Module | Imported Gradle module identities | Weighted links between source and target modules |

Summary, topology, and communities convert the chosen projection to a compact
adjacency graph. They use iterative connected-component and
strongly-connected-component routines without another backend round trip.
The SQL loaders collapse rows with the same endpoints before allocation while
retaining both the typed occurrence-group count and the total relation weight.
Nodes and neighbors stay in SQLite and do not allocate the full graph.

Neighbors remain a typed view. Their bounded SQL reads return relation kind and
context rather than using the collapsed analytics representation.

When a repository base snapshot is available, overlay rows replace base rows
for changed files and tombstones remove deleted files. The effective graph
still receives one generation check before and after computation.

## Consistency failure

Nodes use keyset pagination instead of an unbounded offset scan. Neighbor reads
run inside a pinned read transaction and use source- and target-key indexes.
Every operation checks the requested generation before work and the live
generation before returning. A concurrent refresh therefore returns a typed
stale-generation failure, never a mixed graph.
