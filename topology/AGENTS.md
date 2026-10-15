---
type: Module Guide
title: "Kast topology"
description: "Defines topology facts, live semantic dependency proofs, callback fact reuse, and separate historical graph operations."
resource: "repo://topology"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: topology/build/build.gradle.kts
  - path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt
  - path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt
  - path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt
  - path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackStoreRetirementTest.kt
  - path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackSupplierStoreTest.kt
  - path: topology/contract/build.gradle.kts
  - path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencyInventory.kt
  - path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
  - path: topology/intellij/build.gradle.kts
  - path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt
  - path: topology/service/build.gradle.kts
  - path: topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 1264ed21d715 -->

# topology

## Purpose

Defines topology facts, live semantic dependency proofs, callback fact reuse, and separate historical graph operations.

## Key Files

- [SemanticDependencySnapshot.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt) - dependency continuity for one live read authority.
- [SemanticDependencyInventory.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencyInventory.kt) - the detached dependency inventory.
- [SemanticCallbackFactStore.kt](build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt) - project-owned bounded callback summaries and supplier inventories.
- [TopologyBuildService.kt](build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt) - topology generation and reuse.
- [InstalledIntellijTopologyExtractor.kt](intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt) - native extraction.
- [TopologyGraphService.kt](service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt) - graph indexing and algorithms.
- [HistoricalCaptureCoordinator.kt](build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt) - historical attribution, capture, and publication.

## Subdirectories

- [contract/](contract/) - topology identities, dependency proofs, historical snapshots, and coverage.
- [build/](build/) - generation and live callback fact stores. This directory is a source module.
- [intellij/](intellij/) - native dependency capture and topology extraction.
- [service/](service/) - graph operations over stored topology.

## Entry Points

- Gradle projects: `:topology:contract`, `:topology:build`, `:topology:intellij`, and `:topology:service`.
- [SemanticCallbackStoreRetirementTest.kt](build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackStoreRetirementTest.kt) - store lifetime and retirement cases.
- [SemanticCallbackSupplierStoreTest.kt](build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackSupplierStoreTest.kt) - supplier inventory reuse cases.

## Navigation Hints

- The hosted runtime uses contract, build, and IntelliJ modules for live semantic dependencies.
- For callback reuse, trace dependency continuity into relation endpoint readmission. Cached facts do not grant semantic authority.
- Historical SQLite publication lives in `:evidence:topology-sqlite`. The hosted runtime does not compose the graph service.
- For historical evidence limits, read `docs/reviews/persistent-topology-snapshots.md` from the repository root.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
