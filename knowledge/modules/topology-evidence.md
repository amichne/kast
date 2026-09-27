---
type: Kotlin Module Group
title: Durable change evidence and retained topology
description: Shipped mutation evidence and separately retained topology extraction, graph services and SQLite snapshots.
resource: file://evidence
tags: [kotlin, topology, evidence, sqlite]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt
  - path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt
  - path: topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt
  - path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt
  - path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologySnapshotStore.kt
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationCommit]
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangeReceiptStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt
---

# Durable change evidence and retained topology

The shipped SQLite adapter stores live change plans, permanent application-attempt claims, mutation recovery journals and historical verified receipts. These records support approval, replay prevention and recovery. They do not constitute a semantic index or grant current source-write authority.

`SqliteHostedChangeStores.open` admits one exact mutation-database location, initializes one connection source and retains that capability in the plan, journal and receipt stores. JDBC connections remain scoped to explicit effects. Path admission rejects aliases, symlinks and non-file targets with finite database causes. Plan and receipt schema failures remain distinct variants.

`HostedChangeFailure` preserves state-root, location, database, plan, receipt and plan-lookup failures through endpoint serialization and bounded diagnostic observations. Missing plans, corrupt rows, incompatible versions and I/O rejection remain distinct. Successful open and lookup stages emit completion evidence without paths or source payloads.

The topology contract/build/service/IntelliJ modules remain active and buildable for upcoming graph work. They retain complete-generation proofs, generation reuse, compiler-backed extraction, graph algorithms and bounded source-root synchronization. `evidence:topology-sqlite` owns topology snapshot publication, restart-safe storage and relation compilation independently of the shipped mutation adapter. Architecture checks keep these five modules outside the plugin, CLI and coordinator dependency graphs. Existing tests cover extraction rejection, graph identity, snapshot corruption and failed-publication retention.

Production relation and traversal reads use the current IDEA index through the live read authority. Historical published-evidence contracts remain interpretable; the plugin never fabricates a generation from a live epoch. The isolated workspace publication implementation remains retired.

Legacy change tables are preserved when opening the mutation database. Store tests verify that recovery-compatible records survive and that new shared-store initialization creates only mutation tables. Read [change lifecycle](../flows/change-lifecycle.md) before altering persistence semantics.
