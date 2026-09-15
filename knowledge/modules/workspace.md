---
type: Kotlin Module Group
title: Workspace
description: Workspace contracts distinguish published leases from original-owner live IDE reads and preserve their separate admission effects.
resource: file://workspace
tags: [kotlin, workspace, lifecycle, intellij]
timestamp: 2026-09-14T00:00:00Z
code_sources:
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochObservation.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochVfsListener.kt
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, WorkspaceTransitionSnapshot]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease, SemanticReadLeaseGuard]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, SemanticReadIdentity, LiveSemanticReadAuthority, LiveSemanticReadOwner, SemanticReadValidationPort]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
    symbols: [DetachedModelCapture]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryService.kt
---

# Workspace

The active workspace modules are `workspace:contract` and `workspace:intellij-read`. The production plugin consumes the original open IDEA project. It captures the existing imported model, source-root ownership, saved content and one read epoch without running Gradle import or opening another project.

`SemanticReadAuthority` distinguishes historical published leases from `LiveSemanticReadAuthority`. Live authority retains an opaque IDE epoch and the original host identity. Its detached reference can be restored only by that owner against newly admitted freshness. Retirement is terminal; a moved epoch never revives an earlier reference. IDE counters do not become published generations.

`HostedQueryService` creates and ends a request-scoped context, validates authority before and after evaluation, and drains work before releasing its permit. Semantic objects stay inside the admitted read lifetime. Saved documents, committed PSI and source ownership remain explicit obligations.

Published lease and lifecycle types remain available for historical protocol contracts and tests. Their former `workspace:service` transition coordinator and `workspace:intellij` importer are retired and absent from the build. The retained topology publisher accepts complete generations but is not wired into the production plugin. See [historical publication](../flows/workspace-publication.md) and [existing-IDE reads](../flows/hosted-query.md).

Before evaluation, HostedReadDeadline derives a positive HostedSemanticTimeAllowance from remaining host time with a completion reserve. The context carries that allowance into query and diagnostic-scope budgets. An exhausted allowance rejects before the evaluator starts; final freshness validation and cancellation drainage remain required.

Before semantic admission, `HostedReadPublicationAdmission` verifies that typed containment failures carrying the candidate execution report fit the hard hosted-frame cap. Capacity rejection retains the candidate only in diagnostic evidence and publishes no admitted report. Once admitted, the executor retains the actual report through timeout, cancellation and final freshness failures; this does not grant permission to evaluate after the request deadline.

A bounded VFS batch proven outside the root preserves the invalidation signal. A root-touching batch advances it. An oversized batch has unknown relevance and advances conservatively without traversing events; it does not poison a healthy observer or confer fresh read authority. Subsequent reads still require ordinary model, saved-content, PSI, smart-mode and final freshness admission. Malformed bounded paths and exhausted counters remain terminal failures; no reset revives earlier evidence.
