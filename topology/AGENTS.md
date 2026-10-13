<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: 16a344461124 -->

# topology

## Purpose

Owns topology facts, capture contracts, and graph operations. The hosted runtime uses contract, build, and IntelliJ modules for live semantic dependencies. Graph services and historical SQLite publication remain separate from live composition.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt) - complete-generation proof.
- [contract/src/main/kotlin/io/github/amichne/kast/topology/contract/TopologyWorkspaceIdentity.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/TopologyWorkspaceIdentity.kt) - workspace binding.
- [build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt](build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt) - build orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt](service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt) - graph service.
- [intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt](intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt) - hosted extraction.
- [contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalSnapshot.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalSnapshot.kt) - revision-pinned historical facts and explicit coverage.
- [build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt](build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt) - explicit attribution, native capture, and publication sequence.
- [intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt](intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt) - native admission that rejects unproved external-input stability.

## Subdirectories

- `contract` - facts, snapshots, identity, coverage, and build outcomes.
- `build` - generation reuse, build, and publication.
- `service` - graph model, indexing, and algorithms.
- `intellij` - source enumeration, K2 projection, readiness, and extraction.

## Entry Points

- Gradle projects: `:topology:contract`, `:topology:build`, `:topology:service`, `:topology:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/modules/topology-evidence.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- SQLite snapshot storage is retained in `:evidence:topology-sqlite`; it is separate from shipped mutation persistence.
- For stale or mismatched graphs, start with workspace identity and generation proofs, then publication.
- For missing edges, trace extractor -> projection registry -> persisted snapshot -> graph service.
- For historical capture limits and executed evidence, read the [snapshot report](../docs/reviews/persistent-topology-snapshots.md).
