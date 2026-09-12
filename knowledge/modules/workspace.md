---
type: Kotlin Module Group
title: Workspace
description: Workspace contracts distinguish published leases from original-owner live IDE reads and preserve their separate admission effects.
resource: file://workspace
tags: [kotlin, workspace, lifecycle, intellij]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, WorkspaceTransitionSnapshot]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease, SemanticReadLeaseGuard]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, SemanticReadIdentity, LiveSemanticReadAuthority, LiveSemanticReadOwner, SemanticReadValidationPort]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
    symbols: [DetachedModelCapture]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryService.kt
---

# Workspace

The active workspace modules are `workspace:contract` and `workspace:intellij-read`. The production plugin consumes the original open IDEA project. It captures the existing imported model, source-root ownership, saved content and one read epoch without running Gradle import or opening another project.

`SemanticReadAuthority` distinguishes historical published leases from `LiveSemanticReadAuthority`. Live authority retains an opaque IDE epoch and the original host identity. Its detached reference can be restored only by that owner against newly admitted freshness. Retirement is terminal; a moved epoch never revives an earlier reference. IDE counters do not become published generations.

`HostedQueryService` creates and ends a request-scoped context, validates authority before and after evaluation, and drains work before releasing its permit. Semantic objects stay inside the admitted read lifetime. Saved documents, committed PSI and source ownership remain explicit obligations.

Published lease and lifecycle types remain available for historical protocol contracts and tests. Their former `workspace:service` transition coordinator and `workspace:intellij` importer are retired and absent from the build. No production topology publisher issues new evidence generations. See [historical publication](../flows/workspace-publication.md) and [existing-IDE reads](../flows/hosted-query.md).
