---
type: Kotlin Module Group
title: Topology and durable evidence
description: Compiler-extracted graph content is bound to exact workspace generations and published through storage-neutral evidence contracts.
resource: file://topology
tags: [kotlin, topology, evidence, sqlite]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt
    symbols: [CompleteTopologyGeneration]
  - path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/TopologyBuildService.kt
    symbols: [TopologyBuildService]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationAuthority]
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteWorkspaceGenerationPublication.kt
    symbols: [SqliteWorkspaceGenerationPublication]
---

# Topology and durable evidence

Topology contracts distinguish graph content from proof that a generation is complete and bound to one workspace identity. Build services may reuse or publish a generation only after identity and coverage obligations are satisfied.

Evidence contracts keep publication storage-neutral. SQLite adapters own transactions, schemas, recovery journals, and snapshot persistence; consumers receive publication authority rather than database details.

Read [evidence generation](../glossary/evidence-generation.md) before changing cache or publication semantics.
