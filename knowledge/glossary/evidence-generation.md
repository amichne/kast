---
type: Glossary Term
title: Evidence generation
description: A non-negative, monotonically comparable identifier for one atomically published workspace evidence state.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
tags: [glossary, evidence, workspace]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration, EvidenceEnvelope]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [PublishedWorkspaceGeneration]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
---

# Evidence generation

An evidence generation identifies the published workspace state against which a semantic payload was proven. It is not wall-clock time, a cache freshness hint, or a transport sequence number.

Successful semantic payloads retain their generation in an evidence envelope. Workspace publication advances the generation only through its authority, and current-read guards compare it together with the canonical root.
