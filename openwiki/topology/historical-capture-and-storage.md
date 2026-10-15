---
type: System Explanation
title: Historical capture and SQLite storage
description: Traces Git attribution, native capture admission, resumable staging,
  immutable publication, and the current native capture limitation.
tags:
- kast
- topology
code_sources:
- path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt
- path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStaging.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStore.kt
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalSnapshot.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteTopologyRelationIndex.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalArtifactAdmission.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/cli/HistoricalOfflineDocuments.kt
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalCanonicalEncoding.kt
- path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinatorTest.kt
- path: evidence/topology-sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalSqliteStagingTest.kt
- path: evidence/topology-sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalSqliteReaderIsolationTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-f66e1ad798bbb15d28859cdd
    resource: repo://evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStaging.kt
  - id: openwiki-source-086a0f016fa4619d8f091672
    resource: repo://evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStore.kt
  - id: openwiki-source-1d56a149a873088300d4021b
    resource: repo://topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt
  - id: openwiki-source-2227517211515ea63d498717
    resource: repo://topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Historical capture and SQLite storage

## Attribution and compiler evidence have separate owners

`HistoricalCaptureCoordinator` owns attribution, native capture, capture-basis admission, and publication in that order.
The Git adapter supplies revision, source bytes, and blob identities.
The coordinator admits those facts against the capture plan before it calls the native capture port.
Git attribution alone cannot create compiler facts.

The native result must agree with the attributed inputs and exact plan.
Only an admitted `HistoricalCaptureBasis` reaches publication.
Publication must return the same snapshot identity as that basis.
Invalid attribution, Git failure, basis rejection, native rejection, and publication rejection remain distinct outcomes.

## Current IDEA capture fails closed

`IntellijHistoricalCaptureAdapter` rejects the dispatch thread, unavailable projects, mismatched models, stale authority, and excessive module counts.
It uses a cancellable native read and preserves read-preemption failure.
After those admissions, the current implementation returns `EXTERNAL_INPUT_STABILITY_UNPROVEN`.
It does not extract and publish a historical snapshot through this native path.

Imported source membership and module display names do not prove a complete historical model.
An IDEA read action also cannot freeze mutable external compiler inputs.
Equal digests before and after a read cannot exclude an intervening change and revert.
Storage tests and generated historical fixtures do not remove this native limitation.

## Stage complete files before immutable publication

`SqliteHistoricalSnapshotStaging.advance` admits the stage path and exact capture basis.
It creates a new stage atomically or admits the existing stage before it selects more files.
Per-file work and durable progress allow bounded resumption.
The coordinator owns transaction state and connection cleanup across success and failure.

`publish` rejects an incomplete stage and a destination equal to the stage path.
It copies the admitted stage into a prepared candidate and finalizes the candidate identity.
`SqliteHistoricalSnapshotStore` owns immutable publication and later content admission.
A completed storage operation proves the persisted historical contract for its supplied basis.
It does not prove that the current IDEA adapter can produce that basis.

Offline query tests cover reader isolation, cursors, Unicode, capacity, late failure, and resumed storage effects.
Use those tests for storage claims and the native adapter for capture limits.

## Focused tests

- [HistoricalCaptureCoordinatorTest.kt](../../topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinatorTest.kt)
- [HistoricalSqliteStagingTest.kt](../../evidence/topology-sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalSqliteStagingTest.kt)
- [HistoricalSqliteReaderIsolationTest.kt](../../evidence/topology-sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalSqliteReaderIsolationTest.kt)

## Source owners

- [HistoricalCaptureCoordinator.kt](../../topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt)
- [IntellijHistoricalCaptureAdapter.kt](../../topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt)
- [SqliteHistoricalSnapshotStaging.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStaging.kt)
- [SqliteHistoricalSnapshotStore.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/SqliteHistoricalSnapshotStore.kt)
- [HistoricalSnapshot.kt](../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalSnapshot.kt)
- [SqliteTopologyRelationIndex.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteTopologyRelationIndex.kt)
- [HistoricalArtifactAdmission.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalArtifactAdmission.kt)
- [HistoricalOfflineDocuments.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/cli/HistoricalOfflineDocuments.kt)
- [HistoricalCanonicalEncoding.kt](../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalCanonicalEncoding.kt)

## Read next

- [Graph operations](graph-queries.md)
- [Native evidence limits](../development/testing-and-native-qualification.md)
