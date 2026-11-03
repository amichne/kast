---
type: Glossary Term
title: Evidence generation
description: A non-negative, monotonically comparable identifier for one atomically published workspace evidence state.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
tags: [glossary, evidence, workspace]
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration, EvidenceEnvelope, EvidenceBasis]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [PublishedWorkspaceGeneration]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
sources:
  - id: openwiki-source-d69d2f9483e3ef07d3d7af62
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
generated: { by: "codex", at: "2026-11-03T06:09:31.584Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-03T06:09:31.584Z
---

# Evidence generation

An evidence generation identifies the published workspace state against which a semantic payload was proven. It is not wall-clock time, a cache freshness hint, or a transport sequence number.

Published semantic payloads keep their generation through
`EvidenceBasis.Published`. Workspace publication advances the generation only
through its authority, and published read guards compare it with the canonical
root. Live IDE reads use `EvidenceBasis.Live` and a separate host/epoch/content
identity. IDE counters do not become evidence generations.

The production plugin now uses live evidence exclusively. Published-generation types remain for historical contracts, tests and the retained topology implementation. Topology publication is outside the shipped runtime graph; the workspace-publication implementation remains retired.
