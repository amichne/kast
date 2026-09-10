---
type: Kotlin Module Group
title: Workspace
description: Workspace contracts distinguish published leases from original-owner live IDE reads and preserve their separate admission effects.
resource: file://workspace
tags: [kotlin, workspace, lifecycle, intellij]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, WorkspaceTransitionSnapshot]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease, SemanticReadLeaseGuard]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, SemanticReadIdentity, LiveSemanticReadAuthority, LiveSemanticReadOwner, SemanticReadValidationPort]
  - path: workspace/service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt
    symbols: [WorkspaceTransitionCoordinator]
  - path: workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
    symbols: [DetachedModelCapture]
---

# Workspace

Published workspace state advances through a finite lifecycle and becomes readable when a publication is ready. The [transition coordinator](../../workspace/service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt) owns the publication freshness transition, verifies identity around publication, and retains invalidated work for retry.

A `SemanticReadLease` carries the exact canonical workspace root and published evidence generation. Its guard runs an effect only while that proof remains current; movement is a typed outcome rather than a best-effort check.

The closed `SemanticReadAuthority` contract distinguishes that published lease
from `LiveSemanticReadAuthority`. Live authority retains an opaque IDE epoch and
an original host identity. Its detached reference must be restored by the original
owner against newly admitted freshness. It has no published generation and cannot
enter a publication lease guard. Retirement is terminal, and epoch movement never
revives an earlier reference. The saved, PSI-committed content view declares the
required content policy; the host still has to verify content at the read boundary.
Read contracts now consume `SemanticReadAuthority`. `SemanticReadIdentity`
projects detached published or live identity for fingerprints and continuations;
its canonical revision field never invents a generation from an IDE stamp.
`SemanticReadValidationPort` lets the owning host check the current authority
before and after an operation. The published inspection adapter cannot validate
a live authority.

`requirePublished` is the explicit refinement for boundaries that still require
publication. Mutation targets, publication, and retained topology keep their
published lease requirements. Generalized reads do not broaden those effects.

IntelliJ modules own physical root admission, Gradle import/model capture, VFS observation, and passive read epochs. Service and contract code do not acquire that platform authority.

Read [workspace publication](../flows/workspace-publication.md) for the state sequence.
