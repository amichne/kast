---
type: Runtime Flow
title: Historical workspace publication
description: Historical published-generation contracts remain distinct from the sole production live IDE authority.
resource: file://workspace/contract
tags: [workspace, lifecycle, publication]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, TransitionPhase, TransitionRun]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationAuthority]
  - path: settings.gradle.kts
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
---

# Historical workspace publication

Earlier isolated-runtime contracts describe `Dirty -> Settling -> Refreshing -> Reconciling -> Verifying -> Ready`, with a revalidated atomic publication before issuing a generation lease. The lifecycle and generation types remain historical contract vocabulary.

The isolated transition coordinator, importer and topology publication adapters are retired from the active Gradle graph. Production semantic requests instead use the original open IDEA project's live authority. No second importer or persisted topology is started as a read prerequisite.

A historical `SemanticReadLease` still carries the canonical root and a published generation. Its guard permits effects only while that proof remains current. A `LiveSemanticReadAuthority` carries a different host/epoch/content identity and cannot enter that guard. Neither wire decoding nor a diagnostic receipt promotes historical evidence into current authority.

See [workspace](../modules/workspace.md), [existing-IDE reads](hosted-query.md) and [evidence generation](../glossary/evidence-generation.md).
