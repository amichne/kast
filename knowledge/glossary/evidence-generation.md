---
type: Glossary Term
title: Evidence generation
description: A non-negative, monotonically comparable identifier for one atomically published workspace evidence state.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
tags: [glossary, evidence, workspace]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration, EvidenceEnvelope, EvidenceBasis]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [PublishedWorkspaceGeneration]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
---

# Evidence generation

An evidence generation identifies the published workspace state against which a semantic payload was proven. It is not wall-clock time, a cache freshness hint, or a transport sequence number.

Published semantic payloads retain their generation through
`EvidenceBasis.Published`. Workspace publication advances the generation only
through its authority, and published read guards compare it with the canonical
root. Live IDE reads use `EvidenceBasis.Live` and a separate host/epoch/content
identity. IDE counters do not become evidence generations.
