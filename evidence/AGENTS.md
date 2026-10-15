---
type: Module Guide
title: "Kast evidence"
description: "Stores workspace-bound mutation plans, receipts, and recovery records, with a separate adapter for historical topology snapshots."
resource: "repo://evidence"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: evidence/contract/build.gradle.kts
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/HostedWorkspaceStateLocation.kt
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt
  - path: evidence/sqlite/build.gradle.kts
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt
  - path: evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStoresTest.kt
  - path: evidence/topology-sqlite/build.gradle.kts
  - path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStore.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 3cf4c6551236 -->

# evidence

## Purpose

Stores workspace-bound mutation plans, receipts, and recovery records, with a separate adapter for historical topology snapshots.

## Key Files

- [HostedWorkspaceStateLocation.kt](contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/HostedWorkspaceStateLocation.kt) - workspace-bound storage admission.
- [MutationRecoveryRecord.kt](contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt) - durable recovery evidence.
- [SqliteHostedChangeStores.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt) - one admitted database shared by mutation stores.
- [SqliteLiveChangePlanStore.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt) - live plan storage and codec versions.
- [SqliteMutationRecoveryJournal.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt) - mutation recovery records.
- [SqliteHistoricalSnapshotStore.kt](topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStore.kt) - atomic historical publication and immutable reads.

## Subdirectories

- [contract/](contract/) - storage-neutral recovery and historical publication contracts.
- [sqlite/](sqlite/) - live mutation persistence and database admission.
- [topology-sqlite/](topology-sqlite/) - historical topology storage outside the hosted composition.

## Entry Points

- Gradle projects: `:evidence:contract`, `:evidence:sqlite`, and `:evidence:topology-sqlite`.
- [SqliteHostedChangeStoresTest.kt](sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStoresTest.kt) - restart, schema, and path rejection cases.

## Navigation Hints

- Establish workspace identity and storage admission before examining SQL rows.
- Keep incompatible databases intact on rejection. Do not replace unknown storage with a fresh success state.
- Trace live mutation storage through `runtime/hosted` and the change phase owners.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
