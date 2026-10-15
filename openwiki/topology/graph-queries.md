---
type: System Explanation
title: Detached topology graph queries
description: Explains graph admission, deterministic algorithms, retained snapshot
  relations, and their separation from live compiler authority.
tags:
- kast
- topology
code_sources:
- path: topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt
- path: topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/GraphIndex.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologyRelationCompiler.kt
- path: topology/service/src/test/kotlin/io/github/amichne/kast/topology/service/TopologyGraphServiceTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-4f02279174e4a786c385c95b
    resource: repo://evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologyRelationCompiler.kt
  - id: openwiki-source-56f4ff34e1d4eb0cee538448
    resource: repo://topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/GraphIndex.kt
  - id: openwiki-source-e80fc4972b493f50257598c0
    resource: repo://topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Detached topology graph queries

## Open one exact detached snapshot

`TopologyGraphService` accepts a `TopologySnapshotContentReader` and a published snapshot.
The reader either admits the snapshot content or returns a finite read failure.
Only admitted content reaches `GraphIndex`.
This service accepts no live compiler, Gradle, module-model, or workspace capability.

`GraphIndex` orders symbols and edges by canonical identities before it creates adjacency lists.
Traversal uses a breadth-first frontier and visits each reachable symbol once.
An unknown start and a depth overflow remain distinct outcomes.
Reachability returns a shortest path, an unknown endpoint, or an unreachable result.
A known symbol reaches itself through a zero-edge path.

## Graph algorithms preserve snapshot meaning

Cycle enumeration preserves edge identity and canonical order.
Strongly connected components identify mutually reachable groups.
Condensation combines those components into an acyclic graph.
Quotient operations group admitted symbols at the requested level.
Canonical projection supplies a stable representation for the same admitted content.
These algorithms reason about the captured edges, without a claim of current compiler completeness.

## Adapt retained edges to repository traversal

`SqliteTopologyRelationCompiler.open` reads and retains one exact snapshot for a request.
It compares both snapshot identity and manifest before it admits content.
Later one-hop reads use the retained content without another physical snapshot read.
Each request keeps its lease, selector, edge meaning, continuation position, and resource budget.

The adapter rejects a different root, a live lease, a changed generation, and unsupported discovery constraints.
A matching symbol name does not discharge exact endpoint admission.
The adapter preserves published snapshot identity in relation provenance.
Public live semantic operations use the existing IDEA host instead of this detached capability.

Focused tests establish deterministic graph results and snapshot rejection.
They do not establish historical native capture or live dependency reuse.

## Focused tests

- [TopologyGraphServiceTest.kt](../../topology/service/src/test/kotlin/io/github/amichne/kast/topology/service/TopologyGraphServiceTest.kt)

## Source owners

- [TopologyGraphService.kt](../../topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt)
- [GraphIndex.kt](../../topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/GraphIndex.kt)
- [SqliteTopologyRelationCompiler.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologyRelationCompiler.kt)

## Read next

- [Historical capture](historical-capture-and-storage.md)
- [Live reuse](live-dependencies-and-reuse.md)
- [Traversal state](../semantics/traversal/frontiers-and-continuations.md)
