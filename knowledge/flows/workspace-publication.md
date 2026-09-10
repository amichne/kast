---
type: Runtime Flow
title: Workspace publication
description: Workspace signals are settled, refreshed, reconciled, verified, and atomically published before semantic reads receive a generation lease.
resource: file://workspace/service
tags: [workspace, lifecycle, publication]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, TransitionPhase, TransitionRun]
  - path: workspace/service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt
    symbols: [WorkspaceTransitionCoordinator]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease]
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationAuthority]
---

# Workspace publication

```text
signal -> Dirty -> Settling -> Refreshing -> Reconciling -> Verifying
       -> prepare publication -> verify identity again -> commit -> Ready
```

New signals invalidate an in-flight cycle. Failures are classified into cancellation, retry, or a finite blocker with its phase. Publication is prepared and identity is rechecked before commit, so a ready snapshot always carries an exact published generation.

Only then can a semantic read receive a `SemanticReadLease`. The lease guard serializes an effect with invalidation and returns `Moved` without running the effect when the root or generation is no longer current.

See [workspace](../modules/workspace.md) and [evidence generation](../glossary/evidence-generation.md).
