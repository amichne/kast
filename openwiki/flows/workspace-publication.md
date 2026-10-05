---
type: Runtime Flow
title: Historical workspace publication
description: Historical published-generation contracts remain distinct from the sole production live IDE authority.
resource: file://workspace/contract
tags: [workspace, lifecycle, publication]
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, TransitionPhase, TransitionRun]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationCommit]
  - path: settings.gradle.kts
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
sources:
  - id: openwiki-source-edb3a41dbc90e44dedf87643
    resource: repo://evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
  - id: openwiki-source-2bfd7835755c09f5e5d91344
    resource: repo://workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
generated: { by: "codex", at: "2026-10-02T02:00:02.467Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-05T19:11:53.820Z
---

# Historical workspace publication

Earlier isolated-runtime contracts describe `Dirty -> Settling -> Refreshing -> Reconciling -> Verifying -> Ready`, with a revalidated atomic publication before issuing a generation lease. The lifecycle and generation types remain historical contract vocabulary.

The isolated transition coordinator and importer are retired from the active Gradle graph. Topology extraction, graph operations and snapshot publication remain buildable in separate modules for upcoming work. Production semantic requests instead use the original open IDEA project's live authority. No second importer or persisted topology is started as a read prerequisite.

A historical `SemanticReadLease` still carries the canonical root and a published generation. Its guard permits effects only while that proof remains current. A `LiveSemanticReadAuthority` carries a different host/epoch/content identity and cannot enter that guard. Neither wire decoding nor a diagnostic receipt promotes historical evidence into current authority.

See [workspace](../modules/workspace.md), [existing-IDE reads](hosted-query.md) and [evidence generation](../glossary/evidence-generation.md).
