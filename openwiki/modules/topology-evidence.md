---
type: Kotlin Module Group
title: Durable change evidence and retained topology
description: Shipped mutation evidence and separately retained topology extraction, graph services and SQLite snapshots.
resource: file://evidence
tags: [kotlin, topology, evidence, sqlite]
code_sources:
  - path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt
  - path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt
  - path: topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt
  - path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt
  - path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologySnapshotStore.kt
  - path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologyRelationCompiler.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationProviderState.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationProviderLocator.kt
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationCommit]
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangeReceiptStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt
sources:
  - id: openwiki-source-6f467c39d3cd5a49a04bafa6
    resource: repo://evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
  - id: openwiki-source-7fa48d17b46dccf82f4b3238
    resource: repo://runtime/hosted/build.gradle.kts
  - id: openwiki-source-99135b98c4f85c243291f774
    resource: repo://topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt
  - id: openwiki-source-ad89444889e7d842bb226c7d
    resource: repo://topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt
  - id: openwiki-source-3be5d9cf3fcc7a7b479b3444
    resource: repo://topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-16T21:29:39.165Z
---

# Durable change evidence and retained topology

The shipped SQLite adapter stores live change plans, permanent application-attempt claims, mutation recovery journals and historical verified receipts. These records support exact-plan replay prevention and recovery. They do not constitute a semantic index or grant current source-write authority.

`SqliteHostedChangeStores.open` admits one exact mutation-database location, initializes one connection source and retains that capability in the plan, journal and receipt stores. JDBC connections remain scoped to explicit effects. Path admission rejects aliases, symlinks and non-file targets with finite database causes. Plan and receipt schema failures remain distinct variants.

`HostedChangeFailure` preserves state-root, location, database, plan, receipt and plan-lookup failures through endpoint serialization and bounded diagnostic observations. Missing plans, corrupt rows, incompatible versions and I/O rejection remain distinct. Successful open and lookup stages emit completion evidence without paths or source payloads.

The hosted runtime composes `topology:contract`, `topology:build`, and `topology:intellij` for live semantic dependency evidence and callback reuse.
`SemanticDependencySnapshot` keeps complete source and resolution inventories under the original live owner.
`SemanticCallbackFactStore` keeps bounded detached summaries and supplier inventories.
Reuse still needs dependency continuity and fresh compiler admission of the retained endpoints.

The graph service and `evidence:topology-sqlite` remain separate from hosted composition.
They keep generation proofs, compiler-backed extraction, graph algorithms, historical publication, and restart-safe snapshots.
Existing tests cover extraction rejection, graph identity, snapshot corruption, and failed-publication retention.

Retained SQLite relation reads use the shared immutable provider inventory and continuation contract. The `PUBLISHED_TOPOLOGY_V1` provider carries already confirmed published facts, their exact lease, and their snapshot digest. Each resumed page consumes the remaining inventory with an advancing ordinal; it neither rebuilds the relation inventory nor replays consumed facts. A different snapshot under the same generation cannot consume that remainder. Native locators still require fresh compiler confirmation.

Production relation and traversal reads use the current IDEA index through the live read authority. Historical published-evidence contracts remain interpretable; the plugin never fabricates a generation from a live epoch. The isolated workspace publication implementation remains retired.

Legacy change tables are preserved when opening the mutation database. Store tests verify that recovery-compatible records survive and that new shared-store initialization creates only mutation tables. Read [change lifecycle](../flows/change-lifecycle.md) before altering persistence semantics.

## Focused topology and evidence routes

- [Live dependencies and reuse](../topology/live-dependencies-and-reuse.md) explains dependency inventories and callback fact lifetimes.
- [Historical capture and storage](../topology/historical-capture-and-storage.md) explains revision attribution, native capture, and immutable publication.
- [Graph queries](../topology/graph-queries.md) explains graph identity, algorithms, and snapshot-backed relation reads.
- [Mutation receipts and recovery](../changes/receipts-and-recovery.md) explains the separate live mutation database.
