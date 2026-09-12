<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: 16a344461124 -->

# topology

## Purpose

Retains the buildable topology implementation for upcoming graph work: complete generations, compiler-backed extraction, verified snapshots, and graph operations. These modules are outside the current plugin, CLI, and coordinator runtime dependency graphs.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt) - complete-generation proof.
- [contract/src/main/kotlin/io/github/amichne/kast/topology/contract/TopologyWorkspaceIdentity.kt](contract/src/main/kotlin/io/github/amichne/kast/topology/contract/TopologyWorkspaceIdentity.kt) - workspace binding.
- [build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt](build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt) - build orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt](service/src/main/kotlin/io/github/amichne/kast/topology/service/TopologyGraphService.kt) - graph service.
- [intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt](intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt) - hosted extraction.

## Subdirectories

- `contract` - facts, snapshots, identity, coverage, and build outcomes.
- `build` - generation reuse, build, and publication.
- `service` - graph model, indexing, and algorithms.
- `intellij` - source enumeration, K2 projection, readiness, and extraction.

## Entry Points

- Gradle projects: `:topology:contract`, `:topology:build`, `:topology:service`, `:topology:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/topology-evidence.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- SQLite snapshot storage is retained in `:evidence:topology-sqlite`; it is separate from shipped mutation persistence.
- For stale or mismatched graphs, start with workspace identity and generation proofs, then publication.
- For missing edges, trace extractor -> projection registry -> persisted snapshot -> graph service.
