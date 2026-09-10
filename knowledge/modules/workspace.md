---
type: Kotlin Module Group
title: Workspace
description: Workspace contracts and services establish canonical roots, readiness, publication generations, resource admission, and current semantic-read leases.
resource: file://workspace
tags: [kotlin, workspace, lifecycle, intellij]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, WorkspaceTransitionSnapshot]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease, SemanticReadLeaseGuard]
  - path: workspace/service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt
    symbols: [WorkspaceTransitionCoordinator]
  - path: workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
    symbols: [DetachedModelCapture]
---

# Workspace

Workspace state advances through a finite lifecycle and becomes readable only when a publication is ready. The [transition coordinator](../../workspace/service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt) owns the single freshness transition, verifies identity around publication, and retains invalidated work for retry.

A `SemanticReadLease` carries the exact canonical workspace root and published evidence generation. Its guard runs an effect only while that proof remains current; movement is a typed outcome rather than a best-effort check.

IntelliJ modules own physical root admission, Gradle import/model capture, VFS observation, and passive read epochs. Service and contract code do not acquire that platform authority.

Read [workspace publication](../flows/workspace-publication.md) for the state sequence.
